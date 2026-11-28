package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexfit.shared.SessionUserFilter;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Inicio de sesión con Google (NEX-147): el correo verificado se enlaza con la cuenta de NexFit
 * y abre la misma sesión que el login con contraseña. Sin auto-registro.
 *
 * <p>El intercambio real con Google no se simula: el success handler se invoca directamente con
 * un {@link OidcUser} armado, con el {@link AuthService} real y la base real; la cadena OAuth2 se
 * prueba hasta la redirección a Google y hasta el rechazo de un callback sin solicitud previa.
 * El recorrido completo con una cuenta de Google es una prueba manual.
 */
@Testcontainers
@SpringBootTest(properties = {
        "GOOGLE_CLIENT_ID=google-test-id",
        "GOOGLE_CLIENT_SECRET=google-test-secret",
        "NEXFIT_PUBLIC_URL=https://nexfit-vert.vercel.app"
})
@AutoConfigureMockMvc
class GoogleLoginTests {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String FAILURE = "/login?error=google";
    private static final String SUCCESS = "/login?google=1";
    private static final String NEW_ACCOUNT = "/completar-registro";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    AuthService auth;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long newUser(String email, boolean active, boolean platformAdmin, boolean mustChange,
            String lockedUntilSql) {
        int n = SEQ.incrementAndGet();
        return jdbc.queryForObject("""
                INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                                   password_hash, is_platform_admin, is_active, must_change_password, locked_until)
                VALUES ('CC', ?, 'Ana', 'Pérez', DATE '1990-01-01', ?, ?, ?, ?, ?, %s) RETURNING id
                """.formatted(lockedUntilSql), Long.class,
                "G" + n, email, encoder.encode(PASSWORD), platformAdmin, active, mustChange);
    }

    private long newUser(String email) {
        return newUser(email, true, false, false, "NULL");
    }

    private long newOrg(String name) {
        return jdbc.queryForObject("""
                INSERT INTO organizations (name, contact_email, timezone, is_active)
                VALUES (?, 'g@nexfit.test', 'America/Bogota', true) RETURNING id
                """, Long.class, name);
    }

    private void affiliate(long userId, long orgId, String role) {
        long memberId = jdbc.queryForObject("""
                INSERT INTO organization_members (organization_id, user_id, is_active)
                VALUES (?, ?, true) RETURNING id
                """, Long.class, orgId, userId);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST(? AS member_role), ?)",
                memberId, role, orgId);
    }

    /** Eventos del tipo dado de ese actor (null = sin actor) hechos por Google. */
    private int googleEvents(String type, Long actor) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE event_type = ? AND actor_user_id IS NOT DISTINCT FROM ? AND metadata ->> 'method' = 'google'
                """, Integer.class, type, actor);
    }

    private int failedCount(long userId) {
        return jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, userId);
    }

    private static OidcUser oidcUser(String email, boolean verified) {
        return oidcUser(email, verified, null, null);
    }

    private static OidcUser oidcUser(String email, boolean verified, String givenName, String familyName) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "google-sub-1");
        claims.put("email", email);
        claims.put("email_verified", verified);
        if (givenName != null) {
            claims.put("given_name", givenName);
        }
        if (familyName != null) {
            claims.put("family_name", familyName);
        }
        var token = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(300), claims);
        return new DefaultOidcUser(List.of(), token, "sub");
    }

    private record Callback(MockHttpServletRequest request, MockHttpServletResponse response) {
        String redirect() {
            return response.getRedirectedUrl();
        }

        HttpSession session() {
            return request.getSession(false);
        }
    }

    /** Ejecuta el success handler como lo haría el callback de Google; {@code previous} es una sesión ya abierta. */
    private Callback googleCallback(String email, boolean verified, MockHttpSession previous) throws Exception {
        return googleCallback(oidcUser(email, verified), previous);
    }

    private Callback googleCallback(OidcUser user, MockHttpSession previous) throws Exception {
        var handler = new GoogleLoginSuccessHandler(auth);
        var request = new MockHttpServletRequest();
        if (previous != null) {
            request.setSession(previous);
        }
        var response = new MockHttpServletResponse();
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(user, null, List.of());
        handler.onAuthenticationSuccess(request, response, authentication);
        return new Callback(request, response);
    }

    private Callback googleCallback(String email, boolean verified) throws Exception {
        return googleCallback(email, verified, null);
    }

    // ---- Criterio 1: el correo enlaza con la cuenta y abre la misma sesión -------------------

    @Test
    void correoConocidoAbreLaMismaSesionYSeAuditaElInicio() throws Exception {
        long id = newUser("ok@nexfit.test");
        long org = newOrg("Gym Google");
        affiliate(id, org, "admin");

        Callback callback = googleCallback("ok@nexfit.test", true);

        assertThat(callback.redirect()).isEqualTo(SUCCESS);
        HttpSession session = callback.session();
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        assertThat(session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
                .isEqualTo(String.valueOf(id));
        // US-05: una sola afiliación queda elegida sola, igual que en el login con contraseña.
        assertThat(session.getAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE)).isEqualTo(org);
        assertThat(googleEvents("LOGIN", id)).isEqualTo(1);
    }

    @Test
    void elCorreoNoDistingueMayusculasNiEspacios() throws Exception {
        long id = newUser("Maria.Gomez@nexfit.test");
        int usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

        Callback callback = googleCallback("  maria.gomez@NEXFIT.test ", true);

        assertThat(callback.redirect()).isEqualTo(SUCCESS);
        assertThat(callback.session().getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        // NEX-148: entrar a la cuenta existente nunca crea otra ni deja un registro pendiente.
        assertThat(callback.session().getAttribute(GooglePendingRegistration.ATTRIBUTE)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(usersBefore);
    }

    @Test
    void conVariasAfiliacionesLaSesionQuedaSinGimnasioElegido() throws Exception {
        long id = newUser("dos@nexfit.test");
        affiliate(id, newOrg("Gym Uno"), "admin");
        affiliate(id, newOrg("Gym Dos"), "trainer");

        Callback callback = googleCallback("dos@nexfit.test", true);

        assertThat(callback.redirect()).isEqualTo(SUCCESS);
        assertThat(callback.session().getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        assertThat(callback.session().getAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE)).isNull();
    }

    @Test
    void sinAfiliacionesEntraSinGimnasio() throws Exception {
        long id = newUser("sinorg@nexfit.test");

        Callback callback = googleCallback("sinorg@nexfit.test", true);

        assertThat(callback.redirect()).isEqualTo(SUCCESS);
        assertThat(callback.session().getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        assertThat(callback.session().getAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE)).isNull();
    }

    // ---- Criterio 2: todo rechazo es el mismo error genérico y no abre sesión ----------------

    @Test
    void correoSinCuentaVaDirectoACompletarElRegistroSinAbrirSesion() throws Exception {
        Callback callback = googleCallback("nadie@nexfit.test", true);

        // NEX-149: el correo verificado sin cuenta no es el error genérico: va directo a /completar-registro.
        assertThat(callback.redirect()).isEqualTo(NEW_ACCOUNT);
        assertThat(callback.session().getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isNull();
        assertThat(googleEvents("LOGIN_FAILED", null)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = 'nadie@nexfit.test'",
                Integer.class)).isZero();
    }

    @Test
    void correoSinCuentaDejaElPendienteEnUnaSesionConIdNuevo() throws Exception {
        var previous = new MockHttpSession();
        String idBefore = previous.getId();

        Callback callback = googleCallback("  Nuevo.Pendiente@NexFit.test ", true, previous);

        assertThat(callback.redirect()).isEqualTo(NEW_ACCOUNT);
        assertThat(callback.session().getId()).as("anti-fijación: la sesión cambia de id").isNotEqualTo(idBefore);
        assertThat(callback.session().getAttribute(GooglePendingRegistration.ATTRIBUTE))
                .isInstanceOf(Map.class)
                .extracting(v -> ((Map<?, ?>) v).get("email")).isEqualTo("nuevo.pendiente@nexfit.test");
        assertThat(callback.session().getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isNull();
    }

    @Test
    void elPendienteGuardaElNombreYElApellidoDelPerfilDeGoogle() throws Exception {
        Callback callback = googleCallback(oidcUser("perfil@nexfit.test", true, "  Ana María ", "Pérez Gómez"), null);

        assertThat(callback.redirect()).isEqualTo(NEW_ACCOUNT);
        Map<?, ?> pending = (Map<?, ?>) callback.session().getAttribute(GooglePendingRegistration.ATTRIBUTE);
        assertThat(pending.get("email")).isEqualTo("perfil@nexfit.test");
        assertThat(pending.get("firstName")).isEqualTo("Ana María");
        assertThat(pending.get("lastName")).isEqualTo("Pérez Gómez");
    }

    @Test
    void sinNombreEnElPerfilOConNombresLargosElPendienteQuedaVacioOTruncado() throws Exception {
        Callback sinNombre = googleCallback(oidcUser("sinnombre@nexfit.test", true, null, null), null);
        Map<?, ?> vacio = (Map<?, ?>) sinNombre.session().getAttribute(GooglePendingRegistration.ATTRIBUTE);
        assertThat(vacio.get("firstName")).isEqualTo("");
        assertThat(vacio.get("lastName")).isEqualTo("");

        Callback largo = googleCallback(oidcUser("largo@nexfit.test", true, "n".repeat(150), "a".repeat(101)), null);
        Map<?, ?> truncado = (Map<?, ?>) largo.session().getAttribute(GooglePendingRegistration.ATTRIBUTE);
        assertThat((String) truncado.get("firstName")).hasSize(100);
        assertThat((String) truncado.get("lastName")).hasSize(100);
    }

    @Test
    void elPendienteVenceALos30MinutosDelCallback() throws Exception {
        long before = System.currentTimeMillis();

        Callback callback = googleCallback("treinta@nexfit.test", true);

        long after = System.currentTimeMillis();
        Map<?, ?> pending = (Map<?, ?>) callback.session().getAttribute(GooglePendingRegistration.ATTRIBUTE);
        long expiresAt = (Long) pending.get("expiresAt");
        long thirtyMinutes = Duration.ofMinutes(30).toMillis();
        assertThat(GooglePendingRegistration.TTL).isEqualTo(Duration.ofMinutes(30));
        assertThat(expiresAt).isBetween(before + thirtyMinutes, after + thirtyMinutes);
    }

    @Test
    void correoConCuentaYPerfilDeGoogleEntraDirectoSinPendienteNiSegundaCuenta() throws Exception {
        long id = newUser("conperfil@nexfit.test");
        int usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

        Callback callback = googleCallback(oidcUser("conperfil@nexfit.test", true, "Ana", "Pérez"), null);

        assertThat(callback.redirect()).isEqualTo(SUCCESS);
        assertThat(callback.session().getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        assertThat(callback.session().getAttribute(GooglePendingRegistration.ATTRIBUTE)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(usersBefore);
    }

    @Test
    void correoSinCuentaConUnaSesionDeUsuarioAbiertaEsElErrorGenerico() throws Exception {
        var previous = new MockHttpSession();
        previous.setAttribute(SessionUserFilter.USER_ID_ATTRIBUTE, 555L);

        Callback callback = googleCallback("nadie-con-sesion@nexfit.test", true, previous);

        assertThat(callback.redirect()).isEqualTo(FAILURE);
        assertThat(previous.isInvalid()).isFalse();
        assertThat(previous.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(555L);
        assertThat(previous.getAttribute(GooglePendingRegistration.ATTRIBUTE)).isNull();
    }

    @Test
    void correoSinVerificarYSinCuentaNoDejaPendienteNiCreaCuenta() throws Exception {
        int usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

        Callback callback = googleCallback("sinverificar-nadie@nexfit.test", false);

        assertThat(callback.redirect()).isEqualTo(FAILURE);
        assertThat(callback.session()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(usersBefore);
    }

    @Test
    void correoSinVerificarEnGoogleNoEntraAunqueLaCuentaExista() throws Exception {
        long id = newUser("noverif@nexfit.test");

        Callback callback = googleCallback("noverif@nexfit.test", false);

        assertThat(callback.redirect()).isEqualTo(FAILURE);
        assertThat(callback.session()).isNull();
        // Un rechazo no enseña si la cuenta existe: no queda atado al usuario.
        assertThat(googleEvents("LOGIN", id)).isZero();
        assertThat(googleEvents("LOGIN_FAILED", id)).isZero();
    }

    @Test
    void cuentaInactivaNoEntra() throws Exception {
        newUser("inactiva@nexfit.test", false, false, false, "NULL");

        Callback callback = googleCallback("inactiva@nexfit.test", true);

        assertThat(callback.redirect()).isEqualTo(FAILURE);
        assertThat(callback.session()).isNull();
    }

    @Test
    void cuentaBloqueadaNoEntraNiSumaNiAlargaElBloqueo() throws Exception {
        long id = newUser("bloqueada@nexfit.test", true, false, false, "now() + interval '10 minutes'");
        Instant before = jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?",
                java.sql.Timestamp.class, id).toInstant();

        Callback callback = googleCallback("bloqueada@nexfit.test", true);

        assertThat(callback.redirect()).isEqualTo(FAILURE);
        assertThat(callback.session()).isNull();
        assertThat(failedCount(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?",
                java.sql.Timestamp.class, id).toInstant()).isEqualTo(before);
        assertThat(googleEvents("LOGIN_FAILED", id)).isEqualTo(1);
    }

    @Test
    void elMasterNoEntraPorGoogleYQuedaAuditado() throws Exception {
        long id = newUser("master@nexfit.test", true, true, false, "NULL");

        Callback callback = googleCallback("master@nexfit.test", true);

        assertThat(callback.redirect()).isEqualTo(FAILURE);
        assertThat(callback.session()).isNull();
        assertThat(googleEvents("LOGIN", id)).isZero();
        assertThat(googleEvents("LOGIN_FAILED", id)).isEqualTo(1);
    }

    @Test
    void conContrasenaTemporalPendienteNoEntraPorGoogle() throws Exception {
        long id = newUser("temporal@nexfit.test", true, false, true, "NULL");
        jdbc.update("UPDATE users SET temp_password_expires_at = now() + interval '1 hour' WHERE id = ?", id);

        Callback callback = googleCallback("temporal@nexfit.test", true);

        assertThat(callback.redirect()).isEqualTo(FAILURE);
        assertThat(callback.session()).isNull();
        assertThat(googleEvents("LOGIN_FAILED", id)).isEqualTo(1);
    }

    @Test
    void conContrasenaTemporalVencidaNoEntraPorGoogle() throws Exception {
        long id = newUser("vencida@nexfit.test", true, false, true, "NULL");
        jdbc.update("UPDATE users SET temp_password_expires_at = now() - interval '1 hour' WHERE id = ?", id);

        Callback callback = googleCallback("vencida@nexfit.test", true);

        assertThat(callback.redirect()).isEqualTo(FAILURE);
        assertThat(callback.session()).isNull();
    }

    @Test
    void elRechazoNoDejaAutenticadoElContextoDeLaSolicitud() throws Exception {
        googleCallback("nadie2@nexfit.test", true);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ---- Sesión previa ------------------------------------------------------------------

    @Test
    void elIngresoValidoReemplazaLaSesionPrevia() throws Exception {
        long id = newUser("reemplaza@nexfit.test");
        var previous = new MockHttpSession();
        previous.setAttribute(SessionUserFilter.USER_ID_ATTRIBUTE, 999_999L);

        Callback callback = googleCallback("reemplaza@nexfit.test", true, previous);

        assertThat(previous.isInvalid()).isTrue();
        assertThat(callback.session().getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
    }

    @Test
    void elRechazoNoTocaLaSesionPrevia() throws Exception {
        var previous = new MockHttpSession();
        previous.setAttribute(SessionUserFilter.USER_ID_ATTRIBUTE, 777L);

        Callback callback = googleCallback("nadie3@nexfit.test", true, previous);

        assertThat(callback.redirect()).isEqualTo(FAILURE);
        assertThat(previous.isInvalid()).isFalse();
        assertThat(previous.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(777L);
    }

    // ---- La cadena de OAuth2 ---------------------------------------------------------

    @Test
    void laAutorizacionRedirigeAGoogleConLaRedirectUriFijaYLasProtecciones() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/google")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(location).contains("client_id=google-test-id");
        // La redirect URI sale de NEXFIT_PUBLIC_URL (+ /api), no del host de la solicitud.
        assertThat(location).contains("redirect_uri=https://nexfit-vert.vercel.app/api/login/oauth2/code/google");
        assertThat(location).contains("state=").contains("nonce=").contains("code_challenge=");
        assertThat(location).contains("scope=openid%20email%20profile");
    }

    @Test
    void unCallbackSinSolicitudPreviaVuelveAlLoginYNoDejaSesionDeNexfit() throws Exception {
        MvcResult result = mvc.perform(get("/login/oauth2/code/google").param("code", "x").param("state", "y"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(FAILURE);
        HttpSession session = result.getRequest().getSession(false);
        assertThat(session == null || session.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE) == null).isTrue();
    }

    @Test
    void unCallbackFallidoNoDejaUnaSesionQueRecorraLaApi() throws Exception {
        MvcResult result = mvc.perform(get("/login/oauth2/code/google").param("code", "x").param("state", "y"))
                .andReturn();
        HttpSession session = result.getRequest().getSession(false);

        var request = get("/auth/establishments");
        if (session != null) {
            request.session((MockHttpSession) session);
        }
        mvc.perform(request).andExpect(status().isUnauthorized());
    }
}

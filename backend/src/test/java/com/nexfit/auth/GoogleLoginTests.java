package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexfit.shared.SessionUserFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
 * Inicio de sesión con Google (NEX-147): el correo verificado por Google se
 * enlaza con la cuenta NexFit existente y abre la misma sesión que el login
 * con contraseña. Sin auto-registro y sin tocar contraseñas.
 *
 * <p>El flujo OAuth2 completo no se simula: el post-procesador de pruebas de
 * Spring solo arma el SecurityContext, no pasa por el success handler. Por
 * eso el handler se prueba directo (con el AuthService real) y la cadena
 * OAuth2 se prueba con la redirección a Google.
 */
@Testcontainers
@SpringBootTest(properties = {
        "GOOGLE_CLIENT_ID=google-test-id",
        "GOOGLE_CLIENT_SECRET=google-test-secret"
})
@AutoConfigureMockMvc
class GoogleLoginTests {

    private static final String PASSWORD = "Clave-Segura-123";

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

    private long newUser(String email, boolean active, String lockedUntilSql, boolean platformAdmin) {
        int n = SEQ.incrementAndGet();
        return jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active, locked_until)
            VALUES ('CC', ?, 'Ana', 'Pérez', DATE '1990-01-01', ?, ?, ?, ?, %s) RETURNING id
            """.formatted(lockedUntilSql), Long.class, "G" + n, email, encoder.encode(PASSWORD), platformAdmin, active);
    }

    private long newUser(String email) {
        return newUser(email, true, "NULL", false);
    }

    private long newOrg(String name) {
        return jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES (?, 'g@nexfit.test', 'America/Bogota', true) RETURNING id
            """, Long.class, name);
    }

    /** Afilia al usuario con un rol; devuelve el id de organization_members. */
    private long affiliate(long userId, long orgId, String role) {
        long memberId = jdbc.queryForObject("""
            INSERT INTO organization_members (organization_id, user_id, is_active)
            VALUES (?, ?, true) RETURNING id
            """, Long.class, orgId, userId);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST(? AS member_role), ?)",
            memberId, role, orgId);
        return memberId;
    }

    private int events(String type, Long actor) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = ? AND actor_user_id IS NOT DISTINCT FROM ?",
            Integer.class, type, actor);
    }

    private static OidcUser oidcUser(String email, boolean verified) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "google-sub-1");
        claims.put("email", email);
        claims.put("email_verified", verified);
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

    /** Ejecuta el handler como lo haría el callback de Google. */
    private Callback googleCallback(String email, boolean verified) throws Exception {
        var handler = new GoogleLoginSuccessHandler(auth);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(oidcUser(email, verified), null, List.of());
        handler.onAuthenticationSuccess(request, response, authentication);
        return new Callback(request, response);
    }

    // Criterio 1: correo conocido abre la misma sesión
    @Test
    void correoConocidoAbreLaMismaSesionYSeAuditaElInicio() throws Exception {
        long id = newUser("ok@nexfit.test");
        long org = newOrg("Gym Google");
        affiliate(id, org, "admin");

        Callback callback = googleCallback("ok@nexfit.test", true);
        assertThat(callback.redirect()).isEqualTo("/login");
        HttpSession session = callback.session();
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        assertThat(session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
                .isEqualTo(String.valueOf(id));
        // US-05: una sola afiliación queda elegida sola, igual que en el login con contraseña.
        assertThat(session.getAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE)).isEqualTo(org);
        assertThat(events("LOGIN", id)).isEqualTo(1);
    }

    @Test
    void elCorreoNoDistingueMayusculas() throws Exception {
        long id = newUser("ana@nexfit.test");
        Callback callback = googleCallback("ANA@Nexfit.Test", true);
        assertThat(callback.redirect()).isEqualTo("/login");
        assertThat(callback.session().getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
    }

    @Test
    void conVariasAfiliacionesLaSesionQuedaSinGimnasioElegido() throws Exception {
        long id = newUser("dos@nexfit.test");
        affiliate(id, newOrg("Gym Uno"), "admin");
        affiliate(id, newOrg("Gym Dos"), "admin");

        Callback callback = googleCallback("dos@nexfit.test", true);
        assertThat(callback.redirect()).isEqualTo("/login");
        HttpSession session = callback.session();
        assertThat(session.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        assertThat(session.getAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE)).isNull();
    }

    @Test
    void elMasterEntraSinEstablecimiento() throws Exception {
        long id = newUser("master@nexfit.test", true, "NULL", true);
        Callback callback = googleCallback("master@nexfit.test", true);
        assertThat(callback.redirect()).isEqualTo("/login");
        HttpSession session = callback.session();
        assertThat(session.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        assertThat(session.getAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE)).isNull();
    }

    // Criterio 2: correo sin cuenta, genérico y sin sesión
    @Test
    void correoDesconocidoVuelveAlLoginConErrorGenericoSinAbrirSesion() throws Exception {
        Callback callback = googleCallback("nadie@nexfit.test", true);
        assertThat(callback.redirect()).isEqualTo("/login?error=google");
        assertThat(callback.session()).isNull();
        assertThat(events("LOGIN_FAILED", null)).isEqualTo(1);
    }

    @Test
    void cuentaBloqueadaNoEntraPorGoogleYNoAlargaElBloqueo() throws Exception {
        long id = newUser("bloq@nexfit.test", true, "now() + make_interval(mins => 15)", false);
        Callback callback = googleCallback("bloq@nexfit.test", true);
        assertThat(callback.redirect()).isEqualTo("/login?error=google");
        assertThat(callback.session()).isNull();
        assertThat(events("LOGIN_FAILED", id)).isEqualTo(1);
        assertThat(events("ACCOUNT_LOCKED", id)).isZero();
        Boolean sigueBloqueada = jdbc.queryForObject(
                "SELECT locked_until > now() FROM users WHERE id = ?", Boolean.class, id);
        assertThat(sigueBloqueada).isTrue();
    }

    @Test
    void cuentaInactivaNoEntraPorGoogle() throws Exception {
        long id = newUser("inactivo@nexfit.test", false, "NULL", false);
        Callback callback = googleCallback("inactivo@nexfit.test", true);
        assertThat(callback.redirect()).isEqualTo("/login?error=google");
        assertThat(callback.session()).isNull();
        assertThat(events("LOGIN_FAILED", id)).isEqualTo(1);
    }

    @Test
    void correoSinVerificarEnGoogleNoEntraNiTocaLaBd() throws Exception {
        long id = newUser("sinverificar@nexfit.test");
        Callback callback = googleCallback("sinverificar@nexfit.test", false);
        assertThat(callback.redirect()).isEqualTo("/login?error=google");
        assertThat(callback.session()).isNull();
        assertThat(events("LOGIN", id)).isZero();
        assertThat(events("LOGIN_FAILED", id)).isZero();
    }

    @Test
    void laSesionAbiertaConContrasenaOperaIgualQueLaDeGoogle() throws Exception {
        long id = newUser("opera@nexfit.test");
        long org = newOrg("Gym Opera");
        affiliate(id, org, "admin");

        MvcResult login = mvc.perform(post("/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"opera@nexfit.test\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("NEXFIT_SESSION");
        assertThat(cookie).isNotNull();
        MvcResult establecimientos = mvc.perform(get("/auth/establishments").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(establecimientos.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Gym Opera");
    }

    // Cableado: la cadena OAuth2 existe y redirige a Google con el cliente configurado
    @Test
    void laSolicitudDeAutorizacionRedirigeAGoogleConElClienteConfigurado() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound()).andReturn();
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).contains("accounts.google.com");
        assertThat(location).contains("client_id=google-test-id");
    }
}

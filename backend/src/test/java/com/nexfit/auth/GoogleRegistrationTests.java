package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.legal.LegalPolicyService;
import com.nexfit.shared.SessionUserFilter;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Registro con Google (NEX-148): GET y POST /api/auth/google-registration. El pendiente lo deja el
 * success handler real (como el callback de Google); el intercambio con Google no se simula, igual que
 * en {@link GoogleLoginTests}. El tope por conexión se sube para que estas pruebas no lo agoten; el
 * contador compartido con /auth/register se prueba en {@link GoogleRegistrationRateLimitTests}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "GOOGLE_CLIENT_ID=google-test-id",
        "GOOGLE_CLIENT_SECRET=google-test-secret",
        "NEXFIT_PUBLIC_URL=https://nexfit-vert.vercel.app",
        "nexfit.auth.register-max-per-hour=1000"
})
@AutoConfigureMockMvc
class GoogleRegistrationTests {

    private static final String PATH = "/auth/google-registration";
    private static final String CONFLICT_BODY = "{\"message\":\"Ya existe una cuenta con esos datos\"}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService auth;

    @Autowired
    PasswordEncoder encoder;

    private static final AtomicInteger SEQ = new AtomicInteger();

    // ---- Apoyo -------------------------------------------------------------------------------

    private static OidcUser oidcUser(String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "google-sub-reg");
        claims.put("email", email);
        claims.put("email_verified", true);
        var token = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(300), claims);
        return new DefaultOidcUser(List.of(), token, "sub");
    }

    /** Sesión que deja el callback real de Google para un correo verificado sin cuenta. */
    private MockHttpSession pendingSession(String email) throws Exception {
        var request = new MockHttpServletRequest();
        new GoogleLoginSuccessHandler(auth).onAuthenticationSuccess(request, new MockHttpServletResponse(),
                new UsernamePasswordAuthenticationToken(oidcUser(email), null, List.of()));
        assertThat(request.getSession(false).getAttribute(GooglePendingRegistration.ATTRIBUTE)).isNotNull();
        return (MockHttpSession) request.getSession(false);
    }

    private static String body(String documentNumber, String acceptPolicy) {
        return """
                {"documentType":"CC","documentNumber":"%s","firstName":"Ana","lastName":"Pérez",
                 "birthDate":"1990-01-01"%s}
                """.formatted(documentNumber, acceptPolicy == null ? "" : ",\"acceptPolicy\":" + acceptPolicy);
    }

    private static String newDocument() {
        return "GR" + SEQ.incrementAndGet();
    }

    private static String newEmail() {
        return "google.reg" + SEQ.incrementAndGet() + "@nexfit.test";
    }

    private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private MvcResult complete(MockHttpSession session, String json) throws Exception {
        return send(post(PATH).with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String content(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private int usersWithEmail(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email);
    }

    private long userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE lower(email) = lower(?)", Long.class, email);
    }

    private int events(String type, long actor, String key, String value) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE event_type = ? AND actor_user_id = ? AND metadata ->> CAST(? AS text) = ?
                """, Integer.class, type, actor, key, value);
    }

    // ---- Criterio 1: sin cuenta → completa los datos y queda como Cliente --------------------

    @Test
    void getDevuelveElCorreoPendienteYLaPoliticaVigente() throws Exception {
        MockHttpSession session = pendingSession("Pendiente.Uno@NexFit.test");

        MvcResult r = send(get(PATH).session(session));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(content(r)).contains("\"email\":\"pendiente.uno@nexfit.test\"")
                .contains("\"version\":\"" + LegalPolicyService.VERSION + "\"")
                .contains("\"effectiveDate\"").contains("\"privacyText\":\"");
    }

    @Test
    void postValidoCreaLaCuentaClienteAbreLaSesionYAudita() throws Exception {
        String email = newEmail();
        MockHttpSession pending = pendingSession(email);

        MvcResult r = complete(pending, body(newDocument(), "true"));

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        long id = userIdByEmail(email);
        assertThat(content(r)).isEqualTo("{\"userId\":" + id + "}");
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id)).isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT is_platform_admin FROM users WHERE id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT temp_password_expires_at IS NULL FROM users WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id))
                .startsWith("{bcrypt}");
        // Cliente sin gimnasio: ni afiliación ni roles.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE user_id = ?",
                Integer.class, id)).isZero();
        // Auditoría: registro con la versión vigente del servidor, e ingreso, ambos por Google.
        assertThat(events("ACCOUNT_SELF_REGISTERED", id, "method", "google")).isEqualTo(1);
        assertThat(events("ACCOUNT_SELF_REGISTERED", id, "policyVersion", LegalPolicyService.VERSION)).isEqualTo(1);
        assertThat(events("LOGIN", id, "method", "google")).isEqualTo(1);
        // La sesión quedó abierta: el pendiente se reemplazó por una sesión de usuario.
        assertThat(pending.isInvalid()).isTrue();
        MockHttpSession opened = (MockHttpSession) r.getRequest().getSession(false);
        assertThat(opened.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        assertThat(opened.getAttribute(GooglePendingRegistration.ATTRIBUTE)).isNull();
        assertThat(send(get("/auth/establishments").session(opened)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void elPendienteEsDeUnSoloUso() throws Exception {
        MockHttpSession pending = pendingSession(newEmail());
        assertThat(complete(pending, body(newDocument(), "true")).getResponse().getStatus()).isEqualTo(201);

        MvcResult again = complete(pending, body(newDocument(), "true"));

        assertThat(again.getResponse().getStatus()).isEqualTo(404);
        assertThat(content(again)).contains("NO_PENDING_REGISTRATION");
    }

    @Test
    void sinAceptarLaPoliticaNoSeCreaLaCuentaYElPendienteSigueVigente() throws Exception {
        String email = newEmail();
        MockHttpSession pending = pendingSession(email);

        for (String accept : new String[] { null, "false", "null" }) {
            MvcResult r = complete(pending, body(newDocument(), accept));
            assertThat(r.getResponse().getStatus()).as("acceptPolicy=" + accept).isEqualTo(400);
            assertThat(content(r)).contains("VALIDATION_ERROR").contains("\"field\":\"acceptPolicy\"");
        }

        assertThat(usersWithEmail(email)).isZero();
        assertThat(send(get(PATH).session(pending)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void laValidacionDeLosDatosEsLaDeRegister() throws Exception {
        MockHttpSession pending = pendingSession(newEmail());
        String ok = "\"documentType\":\"CC\",\"documentNumber\":\"X1\",\"firstName\":\"Ana\","
                + "\"lastName\":\"Pérez\",\"birthDate\":\"1990-01-01\",\"acceptPolicy\":true";
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("documentType", ok.replace("\"documentType\":\"CC\"", "\"documentType\":\"\""));
        cases.put("documentType ", ok.replace("\"documentType\":\"CC\"", "\"documentType\":\"XX\""));
        cases.put("documentNumber", ok.replace("\"documentNumber\":\"X1\"", "\"documentNumber\":\" \""));
        cases.put("documentNumber ", ok.replace("\"documentNumber\":\"X1\"",
                "\"documentNumber\":\"" + "9".repeat(31) + "\""));
        cases.put("firstName", ok.replace("\"firstName\":\"Ana\"", "\"firstName\":\"\""));
        cases.put("lastName", ok.replace("\"lastName\":\"Pérez\"", "\"lastName\":\"" + "p".repeat(101) + "\""));
        cases.put("birthDate", ok.replace("\"birthDate\":\"1990-01-01\"", "\"birthDate\":\"01/01/1990\""));
        cases.put("birthDate ", ok.replace("\"birthDate\":\"1990-01-01\",", ""));

        for (var entry : cases.entrySet()) {
            MvcResult r = complete(pending, "{" + entry.getValue() + "}");
            assertThat(r.getResponse().getStatus()).as(entry.getKey()).isEqualTo(400);
            assertThat(content(r)).as(entry.getKey()).contains("\"field\":\"" + entry.getKey().trim() + "\"");
        }
    }

    @Test
    void sinPendienteGetYPostDan404() throws Exception {
        assertThat(send(get(PATH)).getResponse().getStatus()).isEqualTo(404);
        MvcResult r = send(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(newDocument(), "true")));
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(content(r)).contains("NO_PENDING_REGISTRATION");
    }

    @Test
    void unPendienteVencidoDa404EnGetYPostYNoCreaCuenta() throws Exception {
        String email = newEmail();
        var session = new MockHttpSession();
        session.setAttribute(GooglePendingRegistration.ATTRIBUTE,
                GooglePendingRegistration.value(email, System.currentTimeMillis() - 1_000));

        assertThat(send(get(PATH).session(session)).getResponse().getStatus()).isEqualTo(404);
        var expired = new MockHttpSession();
        expired.setAttribute(GooglePendingRegistration.ATTRIBUTE,
                GooglePendingRegistration.value(email, System.currentTimeMillis() - 1_000));
        assertThat(complete(expired, body(newDocument(), "true")).getResponse().getStatus()).isEqualTo(404);
        assertThat(usersWithEmail(email)).isZero();
    }

    @Test
    void laCuentaCreadaPorGoogleNoEntraConContrasena() throws Exception {
        String email = newEmail();
        complete(pendingSession(email), body(newDocument(), "true"));

        for (String guess : new String[] { "Clave-Segura-123", "", email }) {
            MvcResult r = send(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"" + guess + "\"}"));
            assertThat(r.getResponse().getStatus()).as("contraseña «" + guess + "»").isEqualTo(401);
        }
    }

    // ---- Criterio 2: con cuenta → nunca una segunda --------------------------------------------

    @Test
    void siElCorreoSeCreoEntreTantoElPostDa409SinSegundaFilaYBorraElPendiente() throws Exception {
        String email = newEmail();
        MockHttpSession pending = pendingSession(email);
        jdbc.update("""
                INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                                   password_hash, is_platform_admin, is_active)
                VALUES ('CC', ?, 'Otra', 'Persona', DATE '1985-05-05', ?, ?, false, true)
                """, newDocument(), email.toUpperCase(), encoder.encode("Clave-Segura-123"));

        MvcResult r = complete(pending, body(newDocument(), "true"));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(CONFLICT_BODY);
        assertThat(usersWithEmail(email)).isEqualTo(1);
        assertThat(pending.getAttribute(GooglePendingRegistration.ATTRIBUTE)).isNull();
    }

    @Test
    void unDocumentoYaRegistradoDa409() throws Exception {
        String document = newDocument();
        complete(pendingSession(newEmail()), body(document, "true"));
        String second = newEmail();

        MvcResult r = complete(pendingSession(second), body(document, "true"));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(CONFLICT_BODY);
        assertThat(usersWithEmail(second)).isZero();
    }

    /**
     * Dos envíos del formulario: cada uno con su propio pendiente vigente (la sesión JDBC no se bloquea,
     * así que ambos llegan a la base). El segundo ya no lo frena el pendiente sino la unicidad de
     * lower(email) en el INSERT (ON CONFLICT), y responde 409 sin 500 ni segunda fila.
     */
    @Test
    void dosEnviosConElMismoCorreoDan201Y409PorLaUnicidadDeLaBase() throws Exception {
        String email = newEmail();
        MockHttpSession first = pendingSession(email);
        MockHttpSession second = pendingSession(email);
        assertThat(first.getAttribute(GooglePendingRegistration.ATTRIBUTE)).isNotNull();
        assertThat(second.getAttribute(GooglePendingRegistration.ATTRIBUTE)).isNotNull();

        MvcResult one = complete(first, body(newDocument(), "true"));
        MvcResult two = complete(second, body(newDocument(), "true"));

        assertThat(one.getResponse().getStatus()).isEqualTo(201);
        assertThat(two.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(two)).isEqualTo(CONFLICT_BODY);
        assertThat(usersWithEmail(email)).isEqualTo(1);
    }

    // ---- Criterio 4: protecciones --------------------------------------------------------------

    @Test
    void elPostSinTokenCsrfDa403() throws Exception {
        MockHttpSession pending = pendingSession(newEmail());

        MvcResult r = send(post(PATH).session(pending).contentType(MediaType.APPLICATION_JSON)
                .content(body(newDocument(), "true")));

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(pending.getAttribute(GooglePendingRegistration.ATTRIBUTE)).isNotNull();
    }
}

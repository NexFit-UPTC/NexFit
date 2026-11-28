package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.legal.LegalPolicyService;
import com.nexfit.shared.SessionUserFilter;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Registro con Google (NEX-148): GET y POST /api/auth/google-registration. El pendiente se arma en una
 * sesión REAL del repositorio de Spring Session (la que ve el filtro de MockMvc) y se presenta con la
 * cookie NEXFIT_SESSION escrita por el mismo serializador que usa la aplicación; el callback de Google
 * que lo deja se prueba en {@link GoogleLoginTests}. El tope por conexión se sube para que estas pruebas
 * no lo agoten; el contador compartido con /auth/register se prueba en {@link GoogleRegistrationRateLimitTests}.
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
    private static final String COOKIE = "NEXFIT_SESSION";
    private static final String DOCUMENT_TAKEN_BODY = "{\"code\":\"DOCUMENT_TAKEN\",\"field\":\"documentNumber\","
            + "\"message\":\"Ya hay una cuenta con este número de documento\"}";
    private static final String EMAIL_TAKEN_BODY = "{\"code\":\"EMAIL_TAKEN\",\"field\":\"email\","
            + "\"message\":\"Ya hay una cuenta con este correo\"}";

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
    SessionRepository<?> sessionRepository;

    @Autowired
    DefaultCookieSerializer cookieSerializer;

    private static final AtomicInteger SEQ = new AtomicInteger();

    // ---- Apoyo -------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessions() {
        return (SessionRepository<Session>) sessionRepository;
    }

    /** Cookie NEXFIT_SESSION para ese id, escrita con el serializador de la aplicación (Base64 incluido). */
    private Cookie cookieFor(String sessionId) {
        var response = new MockHttpServletResponse();
        cookieSerializer.writeCookieValue(
                new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, sessionId));
        Cookie cookie = response.getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private String sessionIdOf(Cookie cookie) {
        var request = new MockHttpServletRequest();
        request.setCookies(cookie);
        return cookieSerializer.readCookieValues(request).get(0);
    }

    /** La sesión guardada en el repositorio para esa cookie; null si ya no existe. */
    private Session sessionOf(Cookie cookie) {
        return sessions().findById(sessionIdOf(cookie));
    }

    private Object attribute(Cookie cookie, String name) {
        Session session = sessionOf(cookie);
        assertThat(session).as("la sesión debe seguir existiendo").isNotNull();
        return session.getAttribute(name);
    }

    /** Sesión real con un pendiente vigente, como la que deja el callback para un correo verificado sin cuenta. */
    private Cookie pending(String email) {
        return pendingUntil(email, System.currentTimeMillis() + GooglePendingRegistration.TTL.toMillis());
    }

    private Cookie pendingUntil(String email, long expiresAtMillis) {
        return pendingUntil(email, "", "", expiresAtMillis);
    }

    private Cookie pendingWithNames(String email, String firstName, String lastName) {
        return pendingUntil(email, firstName, lastName,
                System.currentTimeMillis() + GooglePendingRegistration.TTL.toMillis());
    }

    private Cookie pendingUntil(String email, String firstName, String lastName, long expiresAtMillis) {
        Session session = sessions().createSession();
        session.setAttribute(GooglePendingRegistration.ATTRIBUTE, GooglePendingRegistration
                .value(email.trim().toLowerCase(Locale.ROOT), firstName, lastName, expiresAtMillis));
        sessions().save(session);
        return cookieFor(session.getId());
    }

    /** Sesión real de una persona anónima sin ningún pendiente. */
    private Cookie sessionWithoutPending() {
        Session session = sessions().createSession();
        sessions().save(session);
        return cookieFor(session.getId());
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

    private MvcResult complete(Cookie session, String json) throws Exception {
        return send(post(PATH).with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON).content(json));
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
        Cookie session = pending("Pendiente.Uno@NexFit.test");

        MvcResult r = send(get(PATH).cookie(session));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(content(r)).contains("\"email\":\"pendiente.uno@nexfit.test\"")
                .contains("\"version\":\"" + LegalPolicyService.VERSION + "\"")
                .contains("\"effectiveDate\"").contains("\"privacyText\":\"");
    }

    @Test
    void getDevuelveElNombreYElApellidoQueGoogleEntrego() throws Exception {
        Cookie session = pendingWithNames("Con.Perfil@NexFit.test", "Ana María", "Pérez");

        MvcResult r = send(get(PATH).cookie(session));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(content(r)).contains("\"email\":\"con.perfil@nexfit.test\"")
                .contains("\"firstName\":\"Ana María\"").contains("\"lastName\":\"Pérez\"");
    }

    @Test
    void postValidoCreaLaCuentaClienteAbreLaSesionYAudita() throws Exception {
        String email = newEmail();
        Cookie pending = pending(email);

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
        // La sesión quedó abierta: la del pendiente se reemplazó por una sesión de usuario con otro id.
        assertThat(sessionOf(pending)).as("la sesión del pendiente ya no existe").isNull();
        Cookie opened = r.getResponse().getCookie(COOKIE);
        assertThat(opened).isNotNull();
        assertThat(opened.getValue()).isNotEqualTo(pending.getValue());
        assertThat(attribute(opened, SessionUserFilter.USER_ID_ATTRIBUTE)).isEqualTo(id);
        assertThat(attribute(opened, GooglePendingRegistration.ATTRIBUTE)).isNull();
        assertThat(send(get("/auth/establishments").cookie(opened)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void elPendienteEsDeUnSoloUso() throws Exception {
        Cookie pending = pending(newEmail());
        assertThat(complete(pending, body(newDocument(), "true")).getResponse().getStatus()).isEqualTo(201);

        MvcResult again = complete(pending, body(newDocument(), "true"));

        assertThat(again.getResponse().getStatus()).isEqualTo(404);
        assertThat(content(again)).contains("NO_PENDING_REGISTRATION");
    }

    @Test
    void sinAceptarLaPoliticaNoSeCreaLaCuentaYElPendienteSigueVigente() throws Exception {
        String email = newEmail();
        Cookie pending = pending(email);

        for (String accept : new String[] { null, "false", "null" }) {
            MvcResult r = complete(pending, body(newDocument(), accept));
            assertThat(r.getResponse().getStatus()).as("acceptPolicy=" + accept).isEqualTo(400);
            assertThat(content(r)).contains("VALIDATION_ERROR").contains("\"field\":\"acceptPolicy\"");
        }

        assertThat(usersWithEmail(email)).isZero();
        assertThat(send(get(PATH).cookie(pending)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void laValidacionDeLosDatosEsLaDeRegister() throws Exception {
        Cookie pending = pending(newEmail());
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
    void sinPendienteGetYPostDan404YConPendienteLoMismoFunciona() throws Exception {
        String json = body(newDocument(), "true");
        Cookie empty = sessionWithoutPending();

        // Sin cookie y con una sesión real sin pendiente: 404 en GET y en POST.
        assertThat(send(get(PATH)).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(get(PATH).cookie(empty)).getResponse().getStatus()).isEqualTo(404);
        MvcResult noCookie = send(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
        assertThat(noCookie.getResponse().getStatus()).isEqualTo(404);
        assertThat(content(noCookie)).contains("NO_PENDING_REGISTRATION");
        MvcResult noPending = complete(empty, json);
        assertThat(noPending.getResponse().getStatus()).isEqualTo(404);
        assertThat(content(noPending)).contains("NO_PENDING_REGISTRATION");

        // Contraste: con un pendiente vigente, las mismas peticiones dan otra respuesta.
        Cookie pending = pending(newEmail());
        assertThat(send(get(PATH).cookie(pending)).getResponse().getStatus()).isEqualTo(200);
        assertThat(complete(pending, json).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void unPendienteVencidoDa404EnGetYPostYNoCreaCuenta() throws Exception {
        String email = newEmail();
        long past = System.currentTimeMillis() - 1_000;
        Cookie forGet = pendingUntil(email, past);
        Cookie forPost = pendingUntil(email, past);

        assertThat(send(get(PATH).cookie(forGet)).getResponse().getStatus()).isEqualTo(404);
        MvcResult r = complete(forPost, body(newDocument(), "true"));
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(content(r)).contains("NO_PENDING_REGISTRATION");
        assertThat(usersWithEmail(email)).isZero();
        // El vencido se borra al leerlo.
        assertThat(attribute(forGet, GooglePendingRegistration.ATTRIBUTE)).isNull();
    }

    @Test
    void laCuentaCreadaPorGoogleNoEntraConContrasena() throws Exception {
        String email = newEmail();
        complete(pending(email), body(newDocument(), "true"));

        for (String guess : new String[] { "Clave-Segura-123", "", email }) {
            MvcResult r = send(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"" + guess + "\"}"));
            assertThat(r.getResponse().getStatus()).as("contraseña «" + guess + "»").isEqualTo(401);
        }
    }

    // ---- Criterio 2: con cuenta → nunca una segunda --------------------------------------------

    @Test
    void siElCorreoSeCreoEntreTantoElPostDa409EmailTakenSinSegundaFilaYElPendienteSigue() throws Exception {
        String email = newEmail();
        Cookie pending = pending(email);
        jdbc.update("""
                INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                                   password_hash, is_platform_admin, is_active)
                VALUES ('CC', ?, 'Otra', 'Persona', DATE '1985-05-05', ?, ?, false, true)
                """, newDocument(), email.toUpperCase(), encoder.encode("Clave-Segura-123"));
        assertThat(attribute(pending, GooglePendingRegistration.ATTRIBUTE)).isNotNull();

        MvcResult r = complete(pending, body(newDocument(), "true"));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(EMAIL_TAKEN_BODY);
        assertThat(usersWithEmail(email)).isEqualTo(1);
        // NEX-149: el 409 no consume el pendiente (solo se consume al crear la cuenta).
        assertThat(attribute(pending, GooglePendingRegistration.ATTRIBUTE)).isNotNull();
    }

    @Test
    void unDocumentoYaRegistradoDa409DocumentTakenYSePuedeCorregirYReenviarSinVolverAGoogle() throws Exception {
        String document = newDocument();
        complete(pending(newEmail()), body(document, "true"));
        String second = newEmail();
        Cookie pending = pending(second);

        MvcResult r = complete(pending, body(document, "true"));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(DOCUMENT_TAKEN_BODY);
        assertThat(usersWithEmail(second)).isZero();
        assertThat(attribute(pending, GooglePendingRegistration.ATTRIBUTE)).isNotNull();

        // Corrige el documento y reenvía con la misma sesión: crea la cuenta sin pasar otra vez por Google.
        MvcResult retry = complete(pending, body(newDocument(), "true"));

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(usersWithEmail(second)).isEqualTo(1);
        assertThat(sessionOf(pending)).as("ahora sí se consumió").isNull();
    }

    @Test
    void elMismoNumeroConOtroTipoDeDocumentoTambienEsDocumentTaken() throws Exception {
        String document = newDocument();
        complete(pending(newEmail()), body(document, "true"));
        String second = newEmail();

        MvcResult r = complete(pending(second),
                body(document, "true").replace("\"documentType\":\"CC\"", "\"documentType\":\"CE\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(DOCUMENT_TAKEN_BODY);
        assertThat(usersWithEmail(second)).isZero();
    }

    @Test
    void siSeRepitenElDocumentoYElCorreoSeInformaElDocumento() throws Exception {
        String document = newDocument();
        String email = newEmail();
        complete(pending(email), body(document, "true"));

        MvcResult r = complete(pending(email), body(document, "true"));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(DOCUMENT_TAKEN_BODY);
        assertThat(usersWithEmail(email)).isEqualTo(1);
    }

    /**
     * Dos envíos del formulario: cada uno con su propio pendiente vigente (la sesión JDBC no se bloquea,
     * así que ambos llegan a la base). El segundo ya no lo frena el pendiente sino la unicidad de
     * lower(email) en el INSERT (ON CONFLICT), y responde 409 sin 500 ni segunda fila.
     */
    @Test
    void dosEnviosConElMismoCorreoDan201Y409EmailTakenPorLaUnicidadDeLaBase() throws Exception {
        String email = newEmail();
        Cookie first = pending(email);
        Cookie second = pending(email);
        assertThat(attribute(first, GooglePendingRegistration.ATTRIBUTE)).isNotNull();
        assertThat(attribute(second, GooglePendingRegistration.ATTRIBUTE)).isNotNull();

        MvcResult one = complete(first, body(newDocument(), "true"));
        MvcResult two = complete(second, body(newDocument(), "true"));

        assertThat(one.getResponse().getStatus()).isEqualTo(201);
        assertThat(two.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(two)).isEqualTo(EMAIL_TAKEN_BODY);
        assertThat(usersWithEmail(email)).isEqualTo(1);
    }

    // ---- Criterio 4: protecciones --------------------------------------------------------------

    @Test
    void elPostSinTokenCsrfDa403() throws Exception {
        Cookie pending = pending(newEmail());

        MvcResult r = send(post(PATH).cookie(pending).contentType(MediaType.APPLICATION_JSON)
                .content(body(newDocument(), "true")));

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(attribute(pending, GooglePendingRegistration.ATTRIBUTE)).isNotNull();
    }
}

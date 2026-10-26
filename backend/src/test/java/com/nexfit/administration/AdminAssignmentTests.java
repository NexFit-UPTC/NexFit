package com.nexfit.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** US-09 (NEX-34): creación y asignación del Administrador de un establecimiento. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminAssignmentTests {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String NEW_PASSWORD = "Nueva-Clave-456";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private AccessFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new AccessFixtures(jdbc, encoder, mvc);
    }

    /** Usuario sin afiliación, con documento conocido ("T" + n) y contraseña PASSWORD. */
    private long newUser(String email) {
        int n = SEQ.incrementAndGet();
        return jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', ?, 'Ana', 'Pérez', DATE '1990-01-01', ?, ?, false, true) RETURNING id
            """, Long.class, "T" + n, email, encoder.encode(PASSWORD));
    }

    private String documentOf(long userId) {
        return jdbc.queryForObject("SELECT document_number FROM users WHERE id = ?", String.class, userId);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    private String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MvcResult assign(Cookie actor, long orgId, String docType, String docNumber,
                             String first, String last, String birth, String email) throws Exception {
        String json = "{\"documentType\":\"%s\",\"documentNumber\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\",\"birthDate\":\"%s\",\"email\":\"%s\"}"
            .formatted(docType, docNumber, first, last, birth, email);
        return mvc.perform(post("/establishments/{orgId}/admin", orgId).with(csrf()).cookie(actor)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)).andReturn();
    }

    private String temporaryOf(MvcResult r) {
        String marker = "\"temporaryPassword\":\"";
        String b = body(r);
        int from = b.indexOf(marker);
        assertThat(from).isGreaterThanOrEqualTo(0);
        int start = from + marker.length();
        return b.substring(start, b.indexOf('"', start));
    }

    private long userIdOf(MvcResult r) {
        String marker = "\"userId\":";
        String b = body(r);
        int from = b.indexOf(marker);
        assertThat(from).isGreaterThanOrEqualTo(0);
        int start = from + marker.length();
        int end = start;
        while (end < b.length() && Character.isDigit(b.charAt(end))) {
            end++;
        }
        return Long.parseLong(b.substring(start, end));
    }

    private int adminCount(long orgId) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM member_roles
            WHERE organization_id = ? AND role = CAST('admin' AS member_role)
            """, Integer.class, orgId);
    }

    private boolean isAdmin(long userId, long orgId) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM member_roles mr JOIN organization_members om ON om.id = mr.member_id
            WHERE mr.organization_id = ? AND om.user_id = ? AND mr.role = CAST('admin' AS member_role)
            """, Integer.class, orgId, userId) > 0;
    }

    private int auditCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE organization_id = ?", Integer.class, orgId);
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void creaUsuarioNuevoYLoAsignaComoAdmin() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        long orgId = fixtures.org(true);

        int n = SEQ.incrementAndGet();
        MvcResult r = assign(masterCookie, orgId, "CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "nuevo" + n + "@nexfit.test");

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        long userId = userIdOf(r);
        assertThat(temporaryOf(r)).isNotEmpty();
        assertThat(isAdmin(userId, orgId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class, userId))
            .isTrue();
        assertThat(auditCount(orgId)).isEqualTo(1);
    }

    @Test
    @ReglaDeNegocio("RN-05")
    void reemplazarQuitaElRolAnteriorPeroConservaLaAfiliacion() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        AccessFixtures.Org org = fixtures.orgWithAdmin();

        int n = SEQ.incrementAndGet();
        MvcResult r = assign(masterCookie, org.id(), "CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "nuevo" + n + "@nexfit.test");

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(isAdmin(userIdOf(r), org.id())).isTrue();
        assertThat(isAdmin(org.admin().id(), org.id())).isFalse();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM organization_members WHERE id = ? AND is_active", Integer.class, org.adminMemberId()))
            .isEqualTo(1);
        assertThat(auditCount(org.id())).isEqualTo(1);
    }

    @Test
    @ReglaDeNegocio("RN-05")
    void dosReemplazosSeguidosDejanUnSoloAdmin() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        AccessFixtures.Org org = fixtures.orgWithAdmin();

        int n1 = SEQ.incrementAndGet();
        MvcResult r1 = assign(masterCookie, org.id(), "CC", "N" + n1, "Juan", "Ríos", "1995-05-05",
            "nuevo" + n1 + "@nexfit.test");
        assertThat(r1.getResponse().getStatus()).isEqualTo(201);
        long second = userIdOf(r1);

        int n2 = SEQ.incrementAndGet();
        MvcResult r2 = assign(masterCookie, org.id(), "CC", "N" + n2, "María", "López", "1992-03-03",
            "nuevo" + n2 + "@nexfit.test");
        assertThat(r2.getResponse().getStatus()).isEqualTo(201);

        assertThat(adminCount(org.id())).isEqualTo(1);
        assertThat(isAdmin(userIdOf(r2), org.id())).isTrue();
        assertThat(isAdmin(second, org.id())).isFalse();
        assertThat(isAdmin(org.admin().id(), org.id())).isFalse();
        assertThat(auditCount(org.id())).isEqualTo(2);
    }

    @Test
    @ReglaDeNegocio("RN-08")
    void personaExistenteSeAfiliaSinCambiarSuContrasena() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        long orgId = fixtures.org(true);

        int n = SEQ.incrementAndGet();
        String email = "existe" + n + "@nexfit.test";
        long personId = newUser(email);

        MvcResult r = assign(masterCookie, orgId, "CC", documentOf(personId), "Otro", "Nombre",
            "1990-01-01", email);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(userIdOf(r)).isEqualTo(personId);
        assertThat(body(r)).contains("Ana");
        assertThat(isAdmin(personId, orgId)).isTrue();
        MvcResult loginResult = login(email, PASSWORD);
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void correoDeOtraPersonaDaConflicto() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        long orgId = fixtures.org(true);

        int n = SEQ.incrementAndGet();
        String email = "conflicto" + n + "@nexfit.test";
        newUser(email);

        MvcResult r = assign(masterCookie, orgId, "CC", "N" + n, "Juan", "Ríos", "1995-05-05", email);

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void mismoDocumentoConOtroCorreoDaConflicto() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        long orgId = fixtures.org(true);

        int n = SEQ.incrementAndGet();
        String email = "titular" + n + "@nexfit.test";
        long personId = newUser(email);

        MvcResult r = assign(masterCookie, orgId, "CC", documentOf(personId), "Ana", "Pérez",
            "1990-01-01", "otro" + n + "@nexfit.test");

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void noMasterRecibe403() throws Exception {
        long orgId = fixtures.org(true);
        AccessFixtures.Account client = fixtures.actor(orgId, "client");
        Cookie clientCookie = fixtures.login(client);

        int n = SEQ.incrementAndGet();
        MvcResult r = assign(clientCookie, orgId, "CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "nuevo" + n + "@nexfit.test");

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void sinSesionRecibe401() throws Exception {
        long orgId = fixtures.org(true);

        int n = SEQ.incrementAndGet();
        MvcResult r = mvc.perform(post("/establishments/{orgId}/admin", orgId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"documentType\":\"CC\",\"documentNumber\":\"N" + n + "\",\"firstName\":\"Juan\",\"lastName\":\"Ríos\",\"birthDate\":\"1995-05-05\",\"email\":\"nuevo" + n + "@nexfit.test\"}"))
            .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void establecimientoInexistenteDa404() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);

        int n = SEQ.incrementAndGet();
        MvcResult r = assign(masterCookie, 999999999L, "CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "nuevo" + n + "@nexfit.test");

        assertThat(r.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void tipoDeDocumentoInvalidoDa400() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        long orgId = fixtures.org(true);

        int n = SEQ.incrementAndGet();
        MvcResult r = assign(masterCookie, orgId, "XX", "N" + n, "Juan", "Ríos", "1995-05-05",
            "nuevo" + n + "@nexfit.test");

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR");
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void laTemporalSirveDePuntaAPunta() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        long orgId = fixtures.org(true);

        int n = SEQ.incrementAndGet();
        String email = "temporal" + n + "@nexfit.test";
        MvcResult r = assign(masterCookie, orgId, "CC", "N" + n, "Juan", "Ríos", "1995-05-05", email);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        String temporary = temporaryOf(r);

        MvcResult loginResult = login(email, temporary);
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
        Cookie userCookie = loginResult.getResponse().getCookie("NEXFIT_SESSION");

        MvcResult change = mvc.perform(post("/auth/password").with(csrf()).cookie(userCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(temporary, NEW_PASSWORD)))
            .andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(204);

        assertThat(login(email, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, temporary).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @ReglaDeNegocio("RN-08")
    void consultaPorDocumentoEncuentraALaPersona() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        long orgId = fixtures.org(true);

        int n = SEQ.incrementAndGet();
        String email = "consulta" + n + "@nexfit.test";
        long personId = newUser(email);

        MvcResult r = mvc.perform(get("/establishments/{orgId}/admin/lookup", orgId)
            .cookie(masterCookie)
            .param("documentType", "CC")
            .param("documentNumber", documentOf(personId)))
            .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"exists\":true");
        assertThat(body(r)).contains("Ana");
    }

    @Test
    void consultaPorDocumentoInexistenteDiceQueNoExiste() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);
        long orgId = fixtures.org(true);

        MvcResult r = mvc.perform(get("/establishments/{orgId}/admin/lookup", orgId)
            .cookie(masterCookie)
            .param("documentType", "CC")
            .param("documentNumber", "ZZZ999"))
            .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"exists\":false");
    }
}

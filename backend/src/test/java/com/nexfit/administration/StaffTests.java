package com.nexfit.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** US-11 (NEX-36): gestión de personal y roles. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StaffTests {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String NEW_PASSWORD = "Clave-Nueva-456";

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

    private Cookie cookieOf(MvcResult r) {
        return r.getResponse().getCookie("NEXFIT_SESSION");
    }

    private String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private long longValue(MvcResult r, String marker) {
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

    private String stringValue(MvcResult r, String marker) {
        String b = body(r);
        int from = b.indexOf(marker);
        assertThat(from).isGreaterThanOrEqualTo(0);
        int start = from + marker.length();
        return b.substring(start, b.indexOf('"', start));
    }

    private String createJson(String docType, String docNumber, String first, String last,
            String birth, String email, String rolesJson) {
        return "{\"documentType\":\"%s\",\"documentNumber\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\",\"birthDate\":\"%s\",\"email\":\"%s\",\"roles\":[%s]}"
                .formatted(docType, docNumber, first, last, birth, email, rolesJson);
    }

    private String createTrainerJson(String docType, String docNumber, String first, String last,
            String birth, String email, String rolesJson,
            String specialty, String certification, String hireDate) {
        return "{\"documentType\":\"%s\",\"documentNumber\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\",\"birthDate\":\"%s\",\"email\":\"%s\",\"roles\":[%s],\"specialty\":\"%s\",\"certification\":\"%s\",\"hireDate\":\"%s\"}"
                .formatted(docType, docNumber, first, last, birth, email, rolesJson,
                        specialty, certification, hireDate);
    }

    private MvcResult create(Cookie actor, String json) throws Exception {
        return mvc.perform(post("/staff").with(csrf()).cookie(actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private MvcResult patchRoles(Cookie actor, long memberId, String json) throws Exception {
        return mvc.perform(patch("/staff/{memberId}", memberId).with(csrf()).cookie(actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private MvcResult setActive(Cookie actor, long memberId, String action) throws Exception {
        return mvc.perform(post("/staff/{memberId}/" + action, memberId).with(csrf()).cookie(actor))
                .andReturn();
    }

    private MvcResult selectEstablishment(Cookie actor, long orgId) throws Exception {
        return mvc.perform(post("/auth/establishment").with(csrf()).cookie(actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"organizationId\":%d}".formatted(orgId))).andReturn();
    }

    private boolean hasRole(long memberId, String role) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM member_roles
                WHERE member_id = ? AND role = CAST(? AS member_role)
                """, Integer.class, memberId, role) > 0;
    }

    private boolean trainerProfileExists(long memberId) {
        return jdbc.queryForObject("SELECT count(*) FROM trainer_profiles WHERE member_id = ?",
                Integer.class, memberId) > 0;
    }

    private boolean memberIsActive(long memberId) {
        return jdbc.queryForObject("SELECT is_active FROM organization_members WHERE id = ?",
                Boolean.class, memberId);
    }

    private int auditCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE organization_id = ?",
                Integer.class, orgId);
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void creaStaffNuevoConTemporalYAuditoria() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        MvcResult r = create(adminCookie, createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
                "staff" + n + "@nexfit.test", "\"staff\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        long userId = longValue(r, "\"userId\":");
        long memberId = longValue(r, "\"memberId\":");
        assertThat(stringValue(r, "\"temporaryPassword\":\"")).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?",
                Boolean.class, userId)).isTrue();
        assertThat(hasRole(memberId, "staff")).isTrue();
        assertThat(memberIsActive(memberId)).isTrue();
        assertThat(auditCount(org.id())).isEqualTo(1);
    }

    @Test
    void creaEntrenadorConPerfil() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        MvcResult r = create(adminCookie, createTrainerJson("CC", "N" + n, "María", "López",
                "1992-03-03", "entrenador" + n + "@nexfit.test", "\"staff\",\"trainer\"",
                "Fuerza", "Cert A", "2024-01-15"));

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        long memberId = longValue(r, "\"memberId\":");
        assertThat(hasRole(memberId, "trainer")).isTrue();
        assertThat(trainerProfileExists(memberId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT specialty FROM trainer_profiles WHERE member_id = ?",
                String.class, memberId)).isEqualTo("Fuerza");
    }

    @Test
    @ReglaDeNegocio("RN-35")
    void menorDe18Da400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        MvcResult r = create(adminCookie, createJson("CC", "N" + n, "Juan", "Ríos", "2015-06-01",
                "menor" + n + "@nexfit.test", "\"staff\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("birthDate");
    }

    @Test
    @ReglaDeNegocio("RN-08")
    void personaExistenteSeAfiliaSinCambiarContrasena() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        String email = "existe" + n + "@nexfit.test";
        long personId = newUser(email);

        MvcResult r = create(adminCookie, createJson("CC", documentOf(personId), "Otro", "Nombre",
                "1990-01-01", email, "\"staff\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(longValue(r, "\"userId\":")).isEqualTo(personId);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void correoDeOtraPersonaDaConflicto() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        String email = "conflicto" + n + "@nexfit.test";
        newUser(email);

        MvcResult r = create(adminCookie, createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
                email, "\"staff\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void mismoDocumentoConOtroCorreoDaConflicto() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        String email = "titular" + n + "@nexfit.test";
        long personId = newUser(email);

        MvcResult r = create(adminCookie, createJson("CC", documentOf(personId), "Ana", "Pérez",
                "1990-01-01", "otro" + n + "@nexfit.test", "\"staff\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void yaMiembroDa409() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        String json = createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
                "miembro" + n + "@nexfit.test", "\"staff\"");
        assertThat(create(adminCookie, json).getResponse().getStatus()).isEqualTo(201);

        MvcResult r = create(adminCookie, createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
                "miembro" + n + "@nexfit.test", "\"trainer\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void rolAdminEnCreacionDa400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        MvcResult r = create(adminCookie, createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
                "admin" + n + "@nexfit.test", "\"admin\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("roles");
    }

    @Test
    void sinRolAdminRecibe403() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        Cookie clientCookie = fixtures.login(client);

        int n = SEQ.incrementAndGet();
        MvcResult r = create(clientCookie, createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
                "cliente" + n + "@nexfit.test", "\"staff\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @ReglaDeNegocio("RN-03")
    void adminQueSumaStaffConservaAdmin() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        MvcResult r = patchRoles(adminCookie, org.adminMemberId(), "{\"roles\":[\"staff\"]}");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(hasRole(org.adminMemberId(), "admin")).isTrue();
        assertThat(hasRole(org.adminMemberId(), "staff")).isTrue();
        assertThat(mvc.perform(get("/staff").cookie(adminCookie)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @ReglaDeNegocio("RN-03")
    void modificarRolesAuditaYCreaPerfil() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        MvcResult created = create(adminCookie, createJson("CC", "N" + n, "Juan", "Ríos",
                "1995-05-05", "modificar" + n + "@nexfit.test", "\"staff\""));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long memberId = longValue(created, "\"memberId\":");

        MvcResult r = patchRoles(adminCookie, memberId,
                "{\"roles\":[\"staff\",\"trainer\"],\"specialty\":\"Fuerza\"}");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(hasRole(memberId, "trainer")).isTrue();
        assertThat(trainerProfileExists(memberId)).isTrue();
        assertThat(auditCount(org.id())).isEqualTo(2);
    }

    @Test
    void quitarTrainerBorraPerfil() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        MvcResult created = create(adminCookie, createTrainerJson("CC", "N" + n, "María", "López",
                "1992-03-03", "quitar" + n + "@nexfit.test", "\"trainer\"",
                "Fuerza", "Cert A", "2024-01-15"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long memberId = longValue(created, "\"memberId\":");
        assertThat(trainerProfileExists(memberId)).isTrue();

        MvcResult r = patchRoles(adminCookie, memberId, "{\"roles\":[\"staff\"]}");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(hasRole(memberId, "trainer")).isFalse();
        assertThat(trainerProfileExists(memberId)).isFalse();
    }

    @Test
    void desactivadoNoPuedeOperarEnEseGimnasio() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        String email = "desactivar" + n + "@nexfit.test";
        MvcResult created = create(adminCookie, createJson("CC", "N" + n, "Juan", "Ríos",
                "1995-05-05", email, "\"staff\""));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long memberId = longValue(created, "\"memberId\":");
        String temporary = stringValue(created, "\"temporaryPassword\":\"");

        MvcResult tempLogin = login(email, temporary);
        assertThat(tempLogin.getResponse().getStatus()).isEqualTo(200);
        MvcResult change = mvc.perform(post("/auth/password").with(csrf()).cookie(cookieOf(tempLogin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(temporary, NEW_PASSWORD)))
                .andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(204);
        MvcResult staffLogin = login(email, NEW_PASSWORD);
        assertThat(staffLogin.getResponse().getStatus()).isEqualTo(200);
        Cookie staffCookie = cookieOf(staffLogin);

        MvcResult deactivated = setActive(adminCookie, memberId, "deactivate");
        assertThat(deactivated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(deactivated)).contains("\"active\":false");
        assertThat(memberIsActive(memberId)).isFalse();

        MvcResult relogin = login(email, NEW_PASSWORD);
        assertThat(relogin.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(relogin)).contains("\"establishments\":[]");

        assertThat(selectEstablishment(cookieOf(relogin), org.id()).getResponse().getStatus())
                .isEqualTo(400);

    }

    @Test
    void reactivarDevuelveLaOperacion() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        String email = "reactivar" + n + "@nexfit.test";
        MvcResult created = create(adminCookie, createJson("CC", "N" + n, "Juan", "Ríos",
                "1995-05-05", email, "\"staff\""));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long memberId = longValue(created, "\"memberId\":");
        String temporary = stringValue(created, "\"temporaryPassword\":\"");

        MvcResult tempLogin = login(email, temporary);
        MvcResult change = mvc.perform(post("/auth/password").with(csrf()).cookie(cookieOf(tempLogin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(temporary, NEW_PASSWORD)))
                .andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(204);
        Cookie staffCookie = cookieOf(login(email, NEW_PASSWORD));

        assertThat(setActive(adminCookie, memberId, "deactivate").getResponse().getStatus())
                .isEqualTo(200);
        MvcResult reactivated = setActive(adminCookie, memberId, "activate");
        assertThat(reactivated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(reactivated)).contains("\"active\":true");

        MvcResult staffLoginAfter = login(email, NEW_PASSWORD);
        assertThat(staffLoginAfter.getResponse().getStatus()).isEqualTo(200);

        assertThat(selectEstablishment(cookieOf(staffLoginAfter), org.id()).getResponse().getStatus())
                .isEqualTo(204);

    }

    @Test
    void consultarListaYDetalleNoRevelanOtroGimnasio() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie adminCookie = fixtures.login(org.admin());

        int n = SEQ.incrementAndGet();
        MvcResult created = create(adminCookie, createJson("CC", "N" + n, "Juan", "Ríos",
                "1995-05-05", "lista" + n + "@nexfit.test", "\"staff\""));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long memberId = longValue(created, "\"memberId\":");

        MvcResult list = mvc.perform(get("/staff").cookie(adminCookie)).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(list)).contains("\"memberId\":" + memberId);

        MvcResult detail = mvc.perform(get("/staff/{memberId}", memberId).cookie(adminCookie))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);

        AccessFixtures.Org other = fixtures.orgWithAdmin();
        MvcResult foreign = mvc.perform(get("/staff/{memberId}", other.adminMemberId())
                .cookie(adminCookie)).andReturn();
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void sinSesionRecibe401() throws Exception {
        MvcResult r = mvc.perform(get("/staff")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }
}

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

/** US-13 (NEX-39): registrar un cliente o afiliar a una persona existente. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ClientTests {

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

    private long epsId() {
        int n = SEQ.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO health_insurance_providers (name, is_active) VALUES (?, true) RETURNING id",
            Long.class, "EPS Test " + n);
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

    private Cookie staffCookie(AccessFixtures.Org org) throws Exception {
        AccessFixtures.Account staff = fixtures.actor(org.id(), "staff");
        return fixtures.login(staff);
    }

    private String createJson(String docType, String docNumber, String first, String last,
            String birth, String email, String phone, long eps,
            String emergencyName, String emergencyRelationship, String emergencyPhone) {
        return createJsonWithConsent(docType, docNumber, first, last, birth, email, phone, eps,
            emergencyName, emergencyRelationship, emergencyPhone, true, true);
    }

    private String createJsonWithConsent(String docType, String docNumber, String first, String last,
            String birth, String email, String phone, long eps,
            String emergencyName, String emergencyRelationship, String emergencyPhone,
            boolean healthConsent, boolean privacyAccepted) {
        return ("{\"documentType\":\"%s\",\"documentNumber\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\","
                + "\"birthDate\":\"%s\",\"email\":\"%s\",\"phone\":\"%s\",\"epsId\":%d,"
                + "\"emergencyContact\":{\"fullName\":\"%s\",\"relationship\":\"%s\",\"phone\":\"%s\"},"
                + "\"healthConsent\":%s,\"privacyAccepted\":%s}")
                .formatted(docType, docNumber, first, last, birth, email, phone, eps,
                    emergencyName, emergencyRelationship, emergencyPhone, healthConsent, privacyAccepted);
    }

    private String createJsonSinContacto(String docType, String docNumber, String first, String last,
            String birth, String email, String phone, long eps) {
        return ("{\"documentType\":\"%s\",\"documentNumber\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\","
                + "\"birthDate\":\"%s\",\"email\":\"%s\",\"phone\":\"%s\",\"epsId\":%d,"
                + "\"healthConsent\":true,\"privacyAccepted\":true}")
                .formatted(docType, docNumber, first, last, birth, email, phone, eps);
    }

    private MvcResult create(Cookie actor, String json) throws Exception {
        return mvc.perform(post("/clients").with(csrf()).cookie(actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private MvcResult search(Cookie actor, String docType, String docNumber,
            boolean verified, boolean present) throws Exception {
        return mvc.perform(get("/clients/search?documentType=%s&documentNumber=%s&documentVerified=%s&personPresent=%s"
                .formatted(docType, docNumber, verified, present)).cookie(actor)).andReturn();
    }

    private boolean hasRole(long memberId, String role) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM member_roles
                WHERE member_id = ? AND role = CAST(? AS member_role)
                """, Integer.class, memberId, role) > 0;
    }

    private long memberOf(long orgId, long userId) {
        return jdbc.queryForObject(
            "SELECT id FROM organization_members WHERE organization_id = ? AND user_id = ?",
            Long.class, orgId, userId);
    }

    private int auditCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE organization_id = ?",
            Integer.class, orgId);
    }

    private int auditTypeCount(long orgId, String type) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE organization_id = ? AND event_type = ?",
            Integer.class, orgId, type);
    }

    @Test
    void creaClienteNuevoConTemporalEpsContactoYAuditoria() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        long eps = epsId();

        int n = SEQ.incrementAndGet();
        MvcResult r = create(staff, createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "cliente" + n + "@nexfit.test", "3001234567", eps, "Luis Pérez", "Padre", "3000000000"));

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        long userId = longValue(r, "\"userId\":");
        long memberId = longValue(r, "\"memberId\":");
        assertThat(stringValue(r, "\"temporaryPassword\":\"")).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?",
            Boolean.class, userId)).isTrue();
        assertThat(hasRole(memberId, "client")).isTrue();
        assertThat(jdbc.queryForObject("SELECT eps_id FROM organization_members WHERE id = ?",
            Long.class, memberId)).isEqualTo(eps);
        assertThat(jdbc.queryForObject("SELECT privacy_accepted_at IS NOT NULL FROM organization_members WHERE id = ?",
            Boolean.class, memberId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT health_consent_at IS NOT NULL FROM organization_members WHERE id = ?",
            Boolean.class, memberId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM emergency_contacts WHERE member_id = ?",
            Integer.class, memberId)).isEqualTo(1);
        assertThat(auditTypeCount(org.id(), "CLIENT_ACCOUNT_CREATED")).isEqualTo(1);
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void personaExistenteSeAfiliaSinCambiarContrasena() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        int n = SEQ.incrementAndGet();
        String email = "existe" + n + "@nexfit.test";
        long personId = newUser(email);

        MvcResult r = create(staff, createJson("CC", documentOf(personId), "Otro", "Nombre",
            "1990-01-01", email, "3001234567", epsId(), "Luis Pérez", "Padre", "3000000000"));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(longValue(r, "\"userId\":")).isEqualTo(personId);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void personaDeOtroGimnasioSeAfiliaSinDuplicarNiCopiarDatos() throws Exception {
        AccessFixtures.Org orgA = fixtures.orgWithAdmin();
        Cookie staffA = staffCookie(orgA);
        AccessFixtures.Org orgB = fixtures.orgWithAdmin();
        Cookie staffB = staffCookie(orgB);

        int n = SEQ.incrementAndGet();
        String email = "compartido" + n + "@nexfit.test";
        String doc = "N" + n;
        MvcResult created = create(staffA, createJson("CC", doc, "Juan", "Ríos", "1995-05-05",
            email, "3001111111", epsId(), "Contacto A", "Madre", "3001111111"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long userId = longValue(created, "\"userId\":");
        String temporary = stringValue(created, "\"temporaryPassword\":\"");

        MvcResult r = create(staffB, createJson("CC", doc, "Juan", "Ríos", "1995-05-05",
            email, "3002222222", epsId(), "Contacto B", "Padre", "3002222222"));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(longValue(r, "\"userId\":")).isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE document_number = ?",
            Integer.class, doc)).isEqualTo(1);
        long memberB = memberOf(orgB.id(), userId);
        assertThat(hasRole(memberB, "client")).isTrue();
        assertThat(jdbc.queryForObject("SELECT full_name FROM emergency_contacts WHERE member_id = ?",
            String.class, memberB)).isEqualTo("Contacto B");
        assertThat(login(email, temporary).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @ReglaDeNegocio("RN-35")
    void menorDe18Da400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        int n = SEQ.incrementAndGet();
        MvcResult r = create(staff, createJson("CC", "N" + n, "Juan", "Ríos", "2015-06-01",
            "menor" + n + "@nexfit.test", "3001234567", epsId(), "Luis Pérez", "Padre", "3000000000"));

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("birthDate");
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void correoDeOtraPersonaDa409() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        int n = SEQ.incrementAndGet();
        String email = "conflicto" + n + "@nexfit.test";
        newUser(email);

        MvcResult r = create(staff, createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            email, "3001234567", epsId(), "Luis Pérez", "Padre", "3000000000"));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void mismoDocumentoConOtroCorreoDa409() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        int n = SEQ.incrementAndGet();
        String email = "titular" + n + "@nexfit.test";
        long personId = newUser(email);

        MvcResult r = create(staff, createJson("CC", documentOf(personId), "Ana", "Pérez",
            "1990-01-01", "otro" + n + "@nexfit.test", "3001234567", epsId(),
            "Luis Pérez", "Padre", "3000000000"));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void yaMiembroDa409() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        int n = SEQ.incrementAndGet();
        String json = createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "miembro" + n + "@nexfit.test", "3001234567", epsId(), "Luis Pérez", "Padre", "3000000000");
        assertThat(create(staff, json).getResponse().getStatus()).isEqualTo(201);

        assertThat(create(staff, json).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void sinContactoDa400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        int n = SEQ.incrementAndGet();
        MvcResult r = create(staff, createJsonSinContacto("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "sincontacto" + n + "@nexfit.test", "3001234567", epsId()));

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("emergencyContact");
    }

    @Test
    void sinEpsDa404() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        int n = SEQ.incrementAndGet();
        MvcResult r = create(staff, createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "sineps" + n + "@nexfit.test", "3001234567", 999999999L, "Luis Pérez", "Padre", "3000000000"));

        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(r)).contains("EPS");
    }

    @Test
    void sinAutorizacionSaludDa400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        int n = SEQ.incrementAndGet();
        MvcResult r = create(staff, createJsonWithConsent("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "sinautorizacion" + n + "@nexfit.test", "3001234567", epsId(),
            "Luis Pérez", "Padre", "3000000000", false, true));

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("healthConsent");
    }

    @Test
    void sinAceptacionPrivacidadDa400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        int n = SEQ.incrementAndGet();
        MvcResult r = create(staff, createJsonWithConsent("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "sinprivacidad" + n + "@nexfit.test", "3001234567", epsId(),
            "Luis Pérez", "Padre", "3000000000", true, false));

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("privacyAccepted");
    }

    @Test
    @ReglaDeNegocio("RN-07")
    void entrenadorRecibe403() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        AccessFixtures.Account trainer = fixtures.actor(org.id(), "trainer");
        Cookie trainerCookie = fixtures.login(trainer);

        int n = SEQ.incrementAndGet();
        MvcResult r = create(trainerCookie, createJson("CC", "N" + n, "Juan", "Ríos", "1995-05-05",
            "entrenador" + n + "@nexfit.test", "3001234567", epsId(), "Luis Pérez", "Padre", "3000000000"));

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void busquedaSoloStaffYAuditaSinRevelarCorreo() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        AccessFixtures.Account trainer = fixtures.actor(org.id(), "trainer");
        Cookie trainerCookie = fixtures.login(trainer);

        int n = SEQ.incrementAndGet();
        String email = "busqueda" + n + "@nexfit.test";
        long personId = newUser(email);

        MvcResult r = search(staff, "CC", documentOf(personId), true, true);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("Ana").contains("Pérez");
        assertThat(body(r)).doesNotContain(email);
        assertThat(auditTypeCount(org.id(), "PERSON_LOOKUP_BY_DOCUMENT")).isEqualTo(1);

        assertThat(search(trainerCookie, "CC", documentOf(personId), true, true)
            .getResponse().getStatus()).isEqualTo(403);
        assertThat(search(staff, "CC", documentOf(personId), false, true)
            .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void busquedaPersonaNoEncontradaDa404() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);

        assertThat(search(staff, "CC", "NO-EXISTE-1", true, true).getResponse().getStatus())
            .isEqualTo(404);
        assertThat(auditCount(org.id())).isEqualTo(1);
    }
}

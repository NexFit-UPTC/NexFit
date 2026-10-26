package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.ReglaDeNegocio;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** US-04 (NEX-26): restablecimiento asistido de contraseña (RF-103, RN-38, RNF-06, RNF-10). */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthTests.Probe.class)
class AssistedPasswordResetTests {

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

    private long newUser(String email, boolean platformAdmin) {
        int n = SEQ.incrementAndGet();
        return jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', ?, 'Ana', 'Pérez', DATE '1990-01-01', ?, ?, ?, true) RETURNING id
            """, Long.class, "D" + n, email, encoder.encode(PASSWORD), platformAdmin);
    }

    private long newOrg(String name) {
        return jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES (?, 'g@nexfit.test', 'America/Bogota', true) RETURNING id
            """, Long.class, name);
    }

    /** Afiliación con rol de personal (admin, staff o trainer). */
    private void affiliate(long userId, long orgId, String role) {
        long memberId = jdbc.queryForObject("""
            INSERT INTO organization_members (organization_id, user_id, is_active)
            VALUES (?, ?, true) RETURNING id
            """, Long.class, orgId, userId);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST(? AS member_role), ?)",
            memberId, role, orgId);
    }

    /** Afiliación como cliente: la RN-25 exige EPS y contacto de emergencia en la misma sentencia. */
    private void affiliateClient(long userId, long orgId) {
        jdbc.queryForObject("""
            WITH eps AS (INSERT INTO health_insurance_providers (name, is_active) VALUES ('EPS', true) RETURNING id),
                 m AS (INSERT INTO organization_members (organization_id, user_id, eps_id, is_active)
                       SELECT ?, ?, eps.id, true FROM eps RETURNING id),
                 c AS (INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
                       SELECT ?, m.id, 'Luis Pérez', 'Padre', '3000000000', true FROM m),
                 r AS (INSERT INTO member_roles (member_id, role, organization_id)
                       SELECT m.id, 'client', ? FROM m)
            SELECT id FROM m
            """, Long.class, orgId, userId, orgId, orgId);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    private Cookie loginOk(String email) throws Exception {
        MvcResult r = login(email, PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getCookie("NEXFIT_SESSION");
    }

    private String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MvcResult reset(Cookie actor, long targetId) throws Exception {
        return mvc.perform(post("/auth/users/{userId}/password-reset", targetId).with(csrf()).cookie(actor))
            .andReturn();
    }

    /** Extrae la contraseña temporal de la respuesta 200 del restablecimiento. */
    private String temporaryOf(MvcResult r) {
        String marker = "\"temporaryPassword\":\"";
        String b = body(r);
        int from = b.indexOf(marker);
        assertThat(from).isGreaterThanOrEqualTo(0);
        int start = from + marker.length();
        return b.substring(start, b.indexOf('"', start));
    }

    private String hash(long userId) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
    }

    private int probe(Cookie cookie) throws Exception {
        return mvc.perform(get("/test/authorities").cookie(cookie)).andReturn().getResponse().getStatus();
    }

    // Criterio 1: cliente de un solo gimnasio; el Staff de ese gimnasio sí puede restablecerlo.
    // (El gimnasio necesita un Administrador asignado: sin él, operar da 403 ESTABLISHMENT_WITHOUT_ADMIN.)
    @Test
    @ReglaDeNegocio("RN-38")
    void staffRestableceClienteDeSuGimnasio() throws Exception {
        long org = newOrg("Gym A");
        affiliate(newUser("admin1@nexfit.test", false), org, "admin");
        long staff = newUser("staff1@nexfit.test", false);
        affiliate(staff, org, "staff");
        long target = newUser("cliente1@nexfit.test", false);
        affiliateClient(target, org);

        MvcResult r = reset(loginOk("staff1@nexfit.test"), target);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String temp = temporaryOf(r);

        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class, target))
            .isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT temp_password_expires_at > now() + interval '71 hours' FROM users WHERE id = ?",
            Boolean.class, target)).isTrue();

        // La contraseña anterior deja de funcionar y la temporal entra pidiendo el cambio.
        assertThat(login("cliente1@nexfit.test", PASSWORD).getResponse().getStatus()).isEqualTo(401);
        MvcResult conTemp = login("cliente1@nexfit.test", temp);
        assertThat(conTemp.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(conTemp)).contains("\"mustChangePassword\":true");
    }

    // Criterio 1: el Administrador del gimnasio también puede.
    @Test
    @ReglaDeNegocio("RN-38")
    void adminTambienPuedeRestablecer() throws Exception {
        long org = newOrg("Gym A");
        long admin = newUser("admin2@nexfit.test", false);
        affiliate(admin, org, "admin");
        long target = newUser("cliente2@nexfit.test", false);
        affiliateClient(target, org);

        MvcResult r = reset(loginOk("admin2@nexfit.test"), target);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String temp = temporaryOf(r);
        assertThat(login("cliente2@nexfit.test", temp).getResponse().getStatus()).isEqualTo(200);
    }

    // Criterio 2: varias afiliaciones y ya ingresó alguna vez → solo el Master.
    @Test
    @ReglaDeNegocio("RN-38")
    void multiAfiliacionConIngresoPrevioSoloMaster() throws Exception {
        long orgA = newOrg("Gym A");
        long orgB = newOrg("Gym B");
        affiliate(newUser("admin3@nexfit.test", false), orgA, "admin");
        long staff = newUser("staff3@nexfit.test", false);
        affiliate(staff, orgA, "staff");
        long target = newUser("cliente3@nexfit.test", false);
        affiliateClient(target, orgA);
        affiliateClient(target, orgB);
        long master = newUser("master3@nexfit.test", true);

        // El objetivo ya ingresó alguna vez (queda su LOGIN en la auditoría).
        assertThat(login("cliente3@nexfit.test", PASSWORD).getResponse().getStatus()).isEqualTo(200);

        String antes = hash(target);
        MvcResult r = reset(loginOk("staff3@nexfit.test"), target);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r)).contains("Master");
        assertThat(hash(target)).isEqualTo(antes);

        MvcResult delMaster = reset(loginOk("master3@nexfit.test"), target);
        assertThat(delMaster.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(delMaster)).contains("\"temporaryPassword\"");
    }

    // Criterio 3: varias afiliaciones pero nunca ingresó → el Staff sí puede.
    @Test
    @ReglaDeNegocio("RN-38")
    void multiAfiliacionSinIngresoPrevioStaffPuede() throws Exception {
        long orgA = newOrg("Gym A");
        long orgB = newOrg("Gym B");
        affiliate(newUser("admin4@nexfit.test", false), orgA, "admin");
        long staff = newUser("staff4@nexfit.test", false);
        affiliate(staff, orgA, "staff");
        long target = newUser("cliente4@nexfit.test", false);
        affiliateClient(target, orgA);
        affiliateClient(target, orgB);

        MvcResult r = reset(loginOk("staff4@nexfit.test"), target);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"temporaryPassword\"");
    }

    // Criterio 5: Entrenador y Cliente no pueden restablecer; nada cambia.
    @Test
    @ReglaDeNegocio("RN-38")
    void entrenadorYClienteReciben403YNadaCambia() throws Exception {
        long org = newOrg("Gym A");
        affiliate(newUser("admin5@nexfit.test", false), org, "admin");
        long trainer = newUser("trainer5@nexfit.test", false);
        affiliate(trainer, org, "trainer");
        long clienteActor = newUser("actor5@nexfit.test", false);
        affiliateClient(clienteActor, org);
        long target = newUser("cliente5@nexfit.test", false);
        affiliateClient(target, org);

        String antes = hash(target);
        assertThat(reset(loginOk("trainer5@nexfit.test"), target).getResponse().getStatus()).isEqualTo(403);
        assertThat(reset(loginOk("actor5@nexfit.test"), target).getResponse().getStatus()).isEqualTo(403);
        assertThat(hash(target)).isEqualTo(antes);
    }

    // Criterio 4: el restablecimiento queda auditado (quién lo hizo y a qué cuenta).
    @Test
    @ReglaDeNegocio("RN-38")
    void quedaAuditadoQuienYACualCuenta() throws Exception {
        long org = newOrg("Gym A");
        affiliate(newUser("admin6@nexfit.test", false), org, "admin");
        long staff = newUser("staff6@nexfit.test", false);
        affiliate(staff, org, "staff");
        long target = newUser("cliente6@nexfit.test", false);
        affiliateClient(target, org);

        MvcResult r = reset(loginOk("staff6@nexfit.test"), target);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String temp = temporaryOf(r);

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_events
            WHERE event_type = 'PASSWORD_RESET' AND actor_user_id = ? AND organization_id = ?
              AND entity_type = 'user' AND entity_id = ?
            """, Integer.class, staff, org, target)).isEqualTo(1);
        String metadata = jdbc.queryForObject("""
            SELECT metadata::text FROM audit_events
            WHERE event_type = 'PASSWORD_RESET' AND actor_user_id = ? AND entity_id = ?
            """, String.class, staff, target);
        assertThat(metadata).doesNotContain(temp);
    }

    // Criterio 6: se levanta el bloqueo y se revocan las sesiones del titular.
    @Test
    @ReglaDeNegocio("RN-38")
    void levantaElBloqueoYRevocaLasSesiones() throws Exception {
        long org = newOrg("Gym A");
        affiliate(newUser("admin7@nexfit.test", false), org, "admin");
        long staff = newUser("staff7@nexfit.test", false);
        affiliate(staff, org, "staff");
        long target = newUser("cliente7@nexfit.test", false);
        affiliateClient(target, org);

        Cookie sesionDelObjetivo = loginOk("cliente7@nexfit.test");
        jdbc.update("UPDATE users SET failed_login_count = 5, locked_until = now() + interval '15 minutes' WHERE id = ?",
            target);
        assertThat(login("cliente7@nexfit.test", PASSWORD).getResponse().getStatus()).isEqualTo(401);

        MvcResult r = reset(loginOk("staff7@nexfit.test"), target);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String temp = temporaryOf(r);

        assertThat(probe(sesionDelObjetivo)).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, target))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?", java.sql.Timestamp.class, target))
            .isNull();
        assertThat(login("cliente7@nexfit.test", temp).getResponse().getStatus()).isEqualTo(200);
    }

    // Fuera de alcance: el objetivo es de otro gimnasio → 404, sin revelar que existe.
    @Test
    @ReglaDeNegocio("RN-38")
    void objetivoDeOtroGimnasioDa404() throws Exception {
        long orgA = newOrg("Gym A");
        long orgB = newOrg("Gym B");
        affiliate(newUser("admin8@nexfit.test", false), orgA, "admin");
        long staff = newUser("staff8@nexfit.test", false);
        affiliate(staff, orgA, "staff");
        long target = newUser("cliente8@nexfit.test", false);
        affiliateClient(target, orgB);

        String antes = hash(target);
        MvcResult r = reset(loginOk("staff8@nexfit.test"), target);
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(r)).contains("Usuario no encontrado");
        assertThat(hash(target)).isEqualTo(antes);
    }

    // Sin sesión → 401.
    @Test
    @ReglaDeNegocio("RN-38")
    void sinSesionDa401() throws Exception {
        long target = newUser("cliente9@nexfit.test", false);
        MvcResult r = mvc.perform(post("/auth/users/{userId}/password-reset", target).with(csrf())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }
}

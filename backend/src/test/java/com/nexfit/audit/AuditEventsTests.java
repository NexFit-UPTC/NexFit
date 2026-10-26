package com.nexfit.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
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

/** NEX-31 (US-47): consultar eventos de auditoría con filtros y ámbito por rol. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuditEventsTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static final String PASSWORD = "Clave-Segura-123";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private record Acct(long id, String email) { }

    private record Org(long id, Acct admin) { }

    // ---------- Criterio 1: el Admin filtra y ve solo su gimnasio ----------

    @Test
    void elAdminFiltraPorFechaUsuarioYTipoYSoloVeSuGimnasio() throws Exception {
        Org gymA = orgWithAdmin();
        Org gymB = orgWithAdmin();
        Acct actor = user(false);
        audit(gymA.id(), actor.id(), "LOGIN", "2026-10-20 10:00:00+00", "a-login");
        audit(gymA.id(), actor.id(), "LOGIN_FAILED", "2026-10-20 11:00:00+00", "a-failed-in");
        audit(gymA.id(), actor.id(), "LOGIN_FAILED", "2026-10-10 09:00:00+00", "a-failed-out");
        audit(gymB.id(), actor.id(), "LOGIN_FAILED", "2026-10-20 11:30:00+00", "b-failed");
        audit(null, actor.id(), "LOGIN_FAILED", "2026-10-20 12:00:00+00", "global-failed");

        Cookie session = login(gymA.admin());
        MvcResult r = mvc.perform(get("/audit/events?from=2026-10-15&to=2026-10-25&userId=" + actor.id()
            + "&type=LOGIN_FAILED").cookie(session)).andReturn();
        String body = body(r);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).contains("a-failed-in")
            .doesNotContain("a-login", "a-failed-out", "b-failed", "global-failed");
    }

    // ---------- Criterio 2: el Master ve toda la plataforma ----------

    @Test
    void elMasterVeTodosLosEventosDeLaPlataforma() throws Exception {
        Org gymA = orgWithAdmin();
        Org gymB = orgWithAdmin();
        Acct actor = user(false);
        audit(gymA.id(), actor.id(), "LOGIN", "2026-10-20 10:00:00+00", "m-a");
        audit(gymB.id(), actor.id(), "PAYMENT_VOIDED", "2026-10-20 11:00:00+00", "m-b");
        audit(null, actor.id(), "LOGIN_FAILED", "2026-10-20 12:00:00+00", "m-global");

        Cookie session = login(user(true));
        MvcResult r = mvc.perform(get("/audit/events").cookie(session)).andReturn();
        String body = body(r);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).contains("m-a", "m-b", "m-global");
    }

    // ---------- Criterio 3: no existe opción de editar o borrar ----------

    @Test
    void noExisteOpcionDeEditarOBorrar() throws Exception {
        Org gymA = orgWithAdmin();
        Cookie session = login(gymA.admin());
        assertThat(mvc.perform(delete("/audit/events").with(csrf()).cookie(session))
            .andReturn().getResponse().getStatus()).isBetween(400, 499);
        assertThat(mvc.perform(put("/audit/events").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getStatus()).isBetween(400, 499);
        assertThat(mvc.perform(patch("/audit/events").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getStatus()).isBetween(400, 499);
    }

    // ---------- Criterio 4: Staff, Entrenador y Cliente reciben 403 ----------

    @Test
    void staffEntrenadorYClienteReciben403() throws Exception {
        Org gymA = orgWithAdmin();
        for (String role : new String[] {"staff", "trainer", "client"}) {
            Acct who = user(false);
            member(who, gymA.id(), role, "3001230001", role.equals("client") ? eps(true) : null);
            Cookie session = login(who);
            assertThat(mvc.perform(get("/audit/events").cookie(session))
                .andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
        }
    }

    // ---------- Sin sesión: 401 ----------

    @Test
    void sinSesionDa401() throws Exception {
        assertThat(mvc.perform(get("/audit/events")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ---------- Cada filtro recorta por su cuenta ----------

    @Test
    void cadaFiltroRecortaPorSuCuenta() throws Exception {
        Org gymA = orgWithAdmin();
        Acct ana = user(false);
        Acct beto = user(false);
        audit(gymA.id(), ana.id(), "LOGIN", "2026-10-20 10:00:00+00", "f-login-ana");
        audit(gymA.id(), ana.id(), "LOGIN_FAILED", "2026-10-20 11:00:00+00", "f-failed-ana");
        audit(gymA.id(), beto.id(), "LOGIN", "2026-10-20 12:00:00+00", "f-login-beto");
        audit(gymA.id(), ana.id(), "LOGIN", "2026-10-05 09:00:00+00", "f-login-viejo");

        Cookie session = login(gymA.admin());
        String porTipo = body(mvc.perform(get("/audit/events?type=LOGIN_FAILED").cookie(session)).andReturn());
        assertThat(porTipo).contains("f-failed-ana").doesNotContain("f-login-ana", "f-login-beto");
        String porUsuario = body(mvc.perform(get("/audit/events?userId=" + beto.id()).cookie(session)).andReturn());
        assertThat(porUsuario).contains("f-login-beto").doesNotContain("f-login-ana", "f-failed-ana");
        String porFecha = body(mvc.perform(get("/audit/events?from=2026-10-15&to=2026-10-25").cookie(session)).andReturn());
        assertThat(porFecha).contains("f-login-ana", "f-failed-ana", "f-login-beto").doesNotContain("f-login-viejo");
    }

    // ---------- Tipo desconocido: 400 ----------

    @Test
    void tipoDesconocidoDa400() throws Exception {
        Org gymA = orgWithAdmin();
        Cookie session = login(gymA.admin());
        MvcResult r = mvc.perform(get("/audit/events?type=NO_EXISTE").cookie(session)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }

    // ---------- El evento devuelto trae metadata e IP intactos ----------

    @Test
    void elEventoDevueltoTraeMetadataEIpIntactos() throws Exception {
        Org gymA = orgWithAdmin();
        Acct actor = user(false);
        jdbc.update("""
            INSERT INTO audit_events (organization_id, actor_user_id, event_type, entity_type, entity_id,
                                      occurred_at, ip_address, metadata)
            VALUES (?, ?, 'PASSWORD_RESET', 'user', ?, CAST('2026-10-20 10:00:00+00' AS timestamptz),
                    CAST('203.0.113.9' AS inet), CAST('{"caso":"integro","detalle":"x"}' AS jsonb))
            """, gymA.id(), actor.id(), actor.id());
        Cookie session = login(gymA.admin());
        String body = body(mvc.perform(get("/audit/events").cookie(session)).andReturn());
        assertThat(body).contains("integro", "203.0.113.9", "2026-10-20");
    }

    // ---------- Datos de prueba ----------

    private Acct user(boolean platformAdmin) {
        int n = SEQ.incrementAndGet();
        String email = "a" + n + "@nexfit.test";
        long id = jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', ?, 'Nombre', 'Pérez', DATE '1990-01-01', ?, ?, ?, true) RETURNING id
            """, Long.class, "A" + n, email, encoder.encode(PASSWORD), platformAdmin);
        return new Acct(id, email);
    }

    /** Gimnasio activo con su Administrador (si no, RN-04 bloquea las rutas operativas). */
    private Org orgWithAdmin() {
        long orgId = jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES ('Gym', 'g@nexfit.test', 'America/Bogota', true) RETURNING id
            """, Long.class);
        Acct admin = user(false);
        member(admin, orgId, "admin", "3000000000", null);
        return new Org(orgId, admin);
    }

    private long eps(boolean active) {
        return jdbc.queryForObject("INSERT INTO health_insurance_providers (name, is_active) VALUES (?, ?) RETURNING id",
            Long.class, "EPS " + SEQ.incrementAndGet(), active);
    }

    /** Afilia al usuario con un rol. El cliente exige EPS y un contacto (RN-25): todo en una sola sentencia. */
    private long member(Acct user, long orgId, String role, String phone, Long epsId) {
        if (role.equals("client")) {
            return jdbc.queryForObject("""
                WITH m AS (INSERT INTO organization_members (organization_id, user_id, phone, eps_id, is_active)
                           VALUES (?, ?, ?, ?, true) RETURNING id),
                     c AS (INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
                           SELECT ?, m.id, 'Luis Pérez', 'Padre', '3000000000', true FROM m),
                     r AS (INSERT INTO member_roles (member_id, role, organization_id)
                           SELECT m.id, 'client', ? FROM m)
                SELECT id FROM m
                """, Long.class, orgId, user.id(), phone, epsId, orgId, orgId);
        }
        long memberId = jdbc.queryForObject("""
            INSERT INTO organization_members (organization_id, user_id, phone, is_active)
            VALUES (?, ?, ?, true) RETURNING id
            """, Long.class, orgId, user.id(), phone);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST(? AS member_role), ?)",
            memberId, role, orgId);
        return memberId;
    }

    /** Inserta un evento de auditoría con fecha y marcador propios (metadata {"caso": marcador}). */
    private void audit(Long orgId, long actorId, String type, String occurredAt, String marker) {
        jdbc.update("""
            INSERT INTO audit_events (organization_id, actor_user_id, event_type, entity_type, entity_id,
                                      occurred_at, ip_address, metadata)
            VALUES (?, ?, ?, 'user', ?, CAST(? AS timestamptz), CAST('192.0.2.1' AS inet),
                    CAST(? AS jsonb))
            """, orgId, actorId, type, actorId, occurredAt, "{\"caso\":\"" + marker + "\"}");
    }

    private Cookie login(Acct account) throws Exception {
        MvcResult r = mvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(account.email(), PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getCookie("NEXFIT_SESSION");
    }

    private static String body(MvcResult r) throws Exception {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}

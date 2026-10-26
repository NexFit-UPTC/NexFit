package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** US-05 (NEX-24): elegir establecimiento y operar con los roles de ese gimnasio. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthTests.Probe.class)
class EstablishmentSelectionTests {

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

    private long newOrg(String name, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES (?, 'g@nexfit.test', 'America/Bogota', ?) RETURNING id
            """, Long.class, name, active);
    }

    private void affiliate(long userId, long orgId, String role, boolean active) {
        long memberId = jdbc.queryForObject("""
            INSERT INTO organization_members (organization_id, user_id, is_active)
            VALUES (?, ?, ?) RETURNING id
            """, Long.class, orgId, userId, active);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST(? AS member_role), ?)",
            memberId, role, orgId);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    private Cookie cookie(MvcResult login) {
        return login.getResponse().getCookie("NEXFIT_SESSION");
    }

    private String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

    }

    private MvcResult selectEstablishment(Cookie cookie, long orgId) throws Exception {
        return mvc.perform(post("/auth/establishment").with(csrf()).cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"organizationId\":%d}".formatted(orgId))).andReturn();
    }

    private String authorities(Cookie cookie) throws Exception {
        return mvc.perform(get("/test/authorities").cookie(cookie)).andReturn()
            .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int probeStatus(Cookie cookie) throws Exception {
        return mvc.perform(get("/test/authorities").cookie(cookie)).andReturn().getResponse().getStatus();
    }

    // Criterio 1: con dos gimnasios activos se ofrecen ambos; sin elegir no se puede operar.
    @Test
    void conDosAfiliacionesSeOfrecenAmbasYSinElegirNoSePuedeOperar() throws Exception {
        long user = newUser("dos@nexfit.test", false);
        long orgA = newOrg("Gym A", true);
        long orgB = newOrg("Gym B", true);
        affiliate(user, orgA, "staff", true);
        affiliate(user, orgB, "trainer", true);

        MvcResult r = login("dos@nexfit.test", PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"organizationId\":" + orgA, "\"organizationId\":" + orgB);

        Cookie c = cookie(r);
        assertThat(probeStatus(c)).isEqualTo(403);
        assertThat(mvc.perform(get("/test/authorities").cookie(c)).andReturn()
            .getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("ESTABLISHMENT_REQUIRED");
    }

    // Criterios 1 y 3: elegir un establecimiento da solo sus roles; cambiarlo cambia los permisos.
    @Test
    void elegirEstablecimientoDaSoloSusRolesYCambiarloCambiaLosPermisos() throws Exception {
        long user = newUser("elige@nexfit.test", false);
        long orgA = newOrg("Gym A", true);
        long orgB = newOrg("Gym B", true);
        affiliate(user, orgA, "staff", true);
        affiliate(user, orgB, "trainer", true);
        long admin = newUser("admin-elige@nexfit.test", false);
        affiliate(admin, orgA, "admin", true);
        affiliate(admin, orgB, "admin", true);

        Cookie c = cookie(login("elige@nexfit.test", PASSWORD));
        assertThat(selectEstablishment(c, orgA).getResponse().getStatus()).isEqualTo(204);
        assertThat(authorities(c)).isEqualTo("[\"staff:" + orgA + "\"]");

        assertThat(selectEstablishment(c, orgB).getResponse().getStatus()).isEqualTo(204);
        assertThat(authorities(c)).isEqualTo("[\"trainer:" + orgB + "\"]");
    }

    // Criterio 2: gimnasio inactivo o afiliación inactiva no se ofrecen ni se pueden elegir.
    @Test
    void gimnasioOAfiliacionInactivosNoSeOfrecenNiSePuedenElegir() throws Exception {
        long user = newUser("inact@nexfit.test", false);
        long orgOk = newOrg("Gym OK", true);
        long orgOff = newOrg("Gym Off", false);
        long orgSusp = newOrg("Gym Susp", true);
        affiliate(user, orgOk, "staff", true);
        affiliate(user, orgOff, "staff", true);
        affiliate(user, orgSusp, "staff", false);

        MvcResult r = login("inact@nexfit.test", PASSWORD);
        assertThat(body(r)).contains("\"organizationId\":" + orgOk);
        assertThat(body(r)).doesNotContain("\"organizationId\":" + orgOff, "\"organizationId\":" + orgSusp);

        Cookie c = cookie(r);
        assertThat(selectEstablishment(c, orgOff).getResponse().getStatus()).isEqualTo(400);
        assertThat(selectEstablishment(c, orgSusp).getResponse().getStatus()).isEqualTo(400);
    }

    // Criterio 2: sin establecimientos el login ofrece lista vacía y no se puede operar.
    @Test
    void sinEstablecimientosElLoginOfreceListaVaciaYNoSePuedeOperar() throws Exception {
        newUser("solo@nexfit.test", false);
        MvcResult r = login("solo@nexfit.test", PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"establishments\":[]");
        assertThat(probeStatus(cookie(r))).isEqualTo(403);
    }

    // Criterio 6 y D-13: el master entra directo al contexto de plataforma, sin elegir.
    @Test
    void masterEntraDirectoSinEstablecimientoYSoloConPlatformAdmin() throws Exception {
        long user = newUser("master@nexfit.test", true);
        long org = newOrg("Gym", true);
        affiliate(user, org, "admin", true);

        MvcResult r = login("master@nexfit.test", PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"establishments\":[]");
        assertThat(authorities(cookie(r))).isEqualTo("[\"PLATFORM_ADMIN\"]");
    }

    // Con una sola afiliación activa se elige sola al iniciar sesión (RN-03: unión de roles).
    @Test
    void unaSolaAfiliacionSeEligeSolaAlIniciarSesion() throws Exception {
        long user = newUser("una@nexfit.test", false);
        long org = newOrg("Gym", true);
        affiliate(user, org, "staff", true);
        affiliate(user, org, "trainer", true);
        affiliate(newUser("admin-una@nexfit.test", false), org, "admin", true);

        Cookie c = cookie(login("una@nexfit.test", PASSWORD));
        assertThat(authorities(c)).isEqualTo("[\"staff:" + org + "\",\"trainer:" + org + "\"]");
    }

    // GET /auth/establishments exige sesión.
    @Test
    void listarEstablecimientosSinSesionDa401() throws Exception {
        mvc.perform(get("/auth/establishments")).andExpect(status().isUnauthorized());
    }

    // GET /auth/establishments devuelve los establecimientos con sus roles.
    @Test
    void listarEstablecimientosDevuelveActivosConRoles() throws Exception {
        long user = newUser("lista@nexfit.test", false);
        long orgA = newOrg("Gym A", true);
        long orgB = newOrg("Gym B", true);
        affiliate(user, orgA, "staff", true);
        affiliate(user, orgB, "trainer", true);

        Cookie c = cookie(login("lista@nexfit.test", PASSWORD));
        MvcResult r = mvc.perform(get("/auth/establishments").cookie(c)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"organizationId\":" + orgA, "\"roles\":[\"staff\"]");
        assertThat(body(r)).contains("\"organizationId\":" + orgB, "\"roles\":[\"trainer\"]");
    }
}

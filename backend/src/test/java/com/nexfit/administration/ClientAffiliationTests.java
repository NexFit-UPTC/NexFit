package com.nexfit.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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

/** US-16 (NEX-44): activar y desactivar la afiliación de un cliente. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ClientAffiliationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private AccessFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new AccessFixtures(jdbc, encoder, mvc);
    }

    // ---- utilidades ----------------------------------------------------------------------

    private MvcResult toggle(Cookie session, long memberId, String action) throws Exception {
        return mvc.perform(post("/clients/" + memberId + "/" + action).with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private MvcResult selectEstablishment(Cookie session, long orgId) throws Exception {
        return mvc.perform(post("/auth/establishment").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{\"organizationId\":%d}".formatted(orgId))).andReturn();
    }

    private MvcResult classes(Cookie session) throws Exception {
        return mvc.perform(get("/classes").cookie(session)).andReturn();
    }

    private static String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private boolean isActive(long memberId) {
        return jdbc.queryForObject("SELECT is_active FROM organization_members WHERE id = ?", Boolean.class, memberId);
    }

    private int audits(long orgId) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM audit_events
             WHERE organization_id = ? AND event_type = 'MEMBERSHIP_AFFILIATION_STATUS_CHANGED'
            """, Integer.class, orgId);
    }

    /** Membresía vigente del cliente, para comprobar que su historial sobrevive a la baja. */
    private void membership(Org org, long memberId) {
        long plan = jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, 'Plan Historial', 50000, 30, true) RETURNING id
            """, Long.class, org.id());
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id,
                                     start_date, end_date, status)
            VALUES (?, ?, ?, ?, CURRENT_DATE - 5, CURRENT_DATE + 25, 'active')
            """, org.id(), memberId, plan, org.adminMemberId());
    }

    // ---- criterio 1: al desactivar, el cliente no opera en el gimnasio ------------------------

    @Test
    void alDesactivarElClienteSeQuedaSinSesionYNoPuedeEntrarAlGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Account client = fx.user(false, true);
        long memberId = fx.member(client, org.id(), "client", true);
        Cookie clientSession = fx.login(client);
        assertThat(status(classes(clientSession))).isEqualTo(200);

        MvcResult r = toggle(staff, memberId, "deactivate");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"memberId\":" + memberId, "\"active\":false");
        assertThat(isActive(memberId)).isFalse();
        assertThat(status(classes(clientSession))).as("la sesión abierta se cierra").isEqualTo(401);
        Cookie again = fx.login(client);
        assertThat(status(selectEstablishment(again, org.id()))).isEqualTo(400);
        assertThat(audits(org.id())).isEqualTo(1);
    }

    @Test
    void elAdministradorTambienPuedeDesactivar() throws Exception {
        Org org = fx.orgWithAdmin();
        long memberId = fx.member(fx.user(false, true), org.id(), "client", true);

        assertThat(status(toggle(fx.login(org.admin()), memberId, "deactivate"))).isEqualTo(200);
        assertThat(isActive(memberId)).isFalse();
    }

    // ---- criterio 2: la afiliación en otro gimnasio no se toca --------------------------------

    @Test
    void siTieneAfiliacionActivaEnOtroGimnasioSigueOperandoAlli() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Account client = fx.user(false, true);
        long here = fx.member(client, org.id(), "client", true);
        long there = fx.member(client, other.id(), "client", true);

        assertThat(status(toggle(staff, here, "deactivate"))).isEqualTo(200);

        assertThat(isActive(here)).isFalse();
        assertThat(isActive(there)).isTrue();
        Cookie session = fx.login(client);
        assertThat(status(selectEstablishment(session, org.id()))).isEqualTo(400);
        assertThat(status(selectEstablishment(session, other.id()))).isEqualTo(204);
        assertThat(status(classes(session))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, client.id()))
            .as("la identidad global no se desactiva").isTrue();
    }

    // ---- criterio 3: al reactivar, el historial sigue disponible ------------------------------

    @Test
    void alReactivarElClienteVuelveAEntrarYConservaSuHistorial() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Account client = fx.user(false, true);
        long memberId = fx.member(client, org.id(), "client", true);
        membership(org, memberId);

        assertThat(status(toggle(staff, memberId, "deactivate"))).isEqualTo(200);
        assertThat(body(mvc.perform(get("/clients/" + memberId).cookie(staff)).andReturn()))
            .contains("\"active\":false", "Plan Historial");

        MvcResult r = toggle(staff, memberId, "activate");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"active\":true");
        assertThat(isActive(memberId)).isTrue();
        assertThat(body(mvc.perform(get("/clients/" + memberId).cookie(staff)).andReturn()))
            .contains("\"active\":true", "Plan Historial");
        assertThat(status(selectEstablishment(fx.login(client), org.id()))).isEqualTo(204);
        assertThat(audits(org.id())).isEqualTo(2);
    }

    @Test
    void repetirLaMismaAccionNoCambiaNadaNiAudita() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        long memberId = fx.member(fx.user(false, true), org.id(), "client", true);

        assertThat(status(toggle(staff, memberId, "activate"))).isEqualTo(200);
        assertThat(audits(org.id())).isZero();
        assertThat(status(toggle(staff, memberId, "deactivate"))).isEqualTo(200);
        assertThat(status(toggle(staff, memberId, "deactivate"))).isEqualTo(200);
        assertThat(audits(org.id())).isEqualTo(1);
        assertThat(isActive(memberId)).isFalse();
    }

    // ---- criterio 4: permisos y aislamiento ---------------------------------------------------

    @Test
    void entrenadorYClienteRecibenForbiddenYNadaCambia() throws Exception {
        Org org = fx.orgWithAdmin();
        long memberId = fx.member(fx.user(false, true), org.id(), "client", true);

        for (String role : new String[] {"trainer", "client"}) {
            Cookie session = fx.login(fx.actor(org.id(), role));
            for (String action : new String[] {"deactivate", "activate"}) {
                MvcResult r = toggle(session, memberId, action);
                assertThat(status(r)).as(role + " " + action).isEqualTo(403);
                assertThat(body(r)).contains("ACCESS_DENIED");
            }
        }
        assertThat(isActive(memberId)).isTrue();
        assertThat(audits(org.id())).isZero();
    }

    @Test
    void sinSesionSeRespondeUnauthorized() throws Exception {
        assertThat(status(mvc.perform(post("/clients/1/deactivate").with(csrf())).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(post("/clients/1/activate").with(csrf())).andReturn())).isEqualTo(401);
    }

    @Test
    void unClienteDeOtroGimnasioOUnInexistenteResponden404Igual() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        long foreign = fx.member(fx.user(false, true), other.id(), "client", true);

        MvcResult theirs = toggle(staff, foreign, "deactivate");
        MvcResult missing = toggle(staff, foreign + 1_000_000, "deactivate");

        assertThat(status(theirs)).isEqualTo(404);
        assertThat(status(missing)).isEqualTo(404);
        assertThat(body(theirs)).isEqualTo(body(missing));
        assertThat(isActive(foreign)).isTrue();
    }

    @Test
    void quienTieneOtroRolSeGestionaDesdePersonalYNoDesdeClientes() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Account both = fx.user(false, true);
        long memberId = fx.member(both, org.id(), "client", true);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST('staff' AS member_role), ?)",
            memberId, org.id());

        MvcResult r = toggle(staff, memberId, "deactivate");

        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("MEMBER_HAS_OTHER_ROLES");
        assertThat(isActive(memberId)).isTrue();
        // Un miembro que no es cliente (aquí, el administrador) ni siquiera se encuentra.
        assertThat(status(toggle(staff, org.adminMemberId(), "deactivate"))).isEqualTo(404);
        assertThat(isActive(org.adminMemberId())).isTrue();
    }
}

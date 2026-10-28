package com.nexfit.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

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

/** NEX-33 (US-08): activar y desactivar establecimientos (baja lógica, RN-33). */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EstablishmentActivationTests {

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

    private MvcResult setActive(Cookie session, long id, String json) throws Exception {
        return mvc.perform(patch("/establishments/" + id + "/active").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private boolean isActive(long orgId) {
        return jdbc.queryForObject("SELECT is_active FROM organizations WHERE id = ?", Boolean.class, orgId);
    }

    private int events(String type, long orgId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE event_type = ? AND organization_id = ?",
            Integer.class, type, orgId);
    }

    // El Master desactiva y reactiva; la respuesta trae el estado nuevo.
    @Test
    void elMasterDesactivaYReactiva() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long orgId = fx.org(true);

        MvcResult off = setActive(master, orgId, "{\"active\":false}");
        assertThat(status(off)).isEqualTo(200);
        assertThat(body(off)).contains("\"id\":" + orgId, "\"active\":false");
        assertThat(isActive(orgId)).isFalse();

        MvcResult on = setActive(master, orgId, "{\"active\":true}");
        assertThat(status(on)).isEqualTo(200);
        assertThat(body(on)).contains("\"active\":true");
        assertThat(isActive(orgId)).isTrue();
    }

    // Cada cambio real deja un evento de auditoría con el Master como actor.
    @Test
    void cadaCambioQuedaAuditado() throws Exception {
        Account masterUser = fx.user(true, true);
        Cookie master = fx.login(masterUser);
        long orgId = fx.org(true);

        setActive(master, orgId, "{\"active\":false}");
        setActive(master, orgId, "{\"active\":true}");

        assertThat(events("ORGANIZATION_DEACTIVATED", orgId)).isEqualTo(1);
        assertThat(events("ORGANIZATION_REACTIVATED", orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_events
             WHERE organization_id = ? AND actor_user_id = ? AND entity_type = 'organization' AND entity_id = ?
            """, Integer.class, orgId, masterUser.id(), orgId)).isEqualTo(2);
    }

    // Repetir el mismo estado no cambia nada ni genera ruido en la auditoría.
    @Test
    void repetirElMismoEstadoEsIdempotenteYNoAudita() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long active = fx.org(true);
        long inactive = fx.org(false);

        assertThat(status(setActive(master, active, "{\"active\":true}"))).isEqualTo(200);
        assertThat(status(setActive(master, inactive, "{\"active\":false}"))).isEqualTo(200);

        assertThat(events("ORGANIZATION_REACTIVATED", active)).isZero();
        assertThat(events("ORGANIZATION_DEACTIVATED", inactive)).isZero();
    }

    // Baja lógica: sedes, afiliaciones y demás datos siguen intactos (el historial se conserva).
    @Test
    void desactivarConservaElHistorial() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        Org org = fx.orgWithAdmin();
        fx.branch(org.id());
        int members = jdbc.queryForObject(
            "SELECT count(*) FROM organization_members WHERE organization_id = ?", Integer.class, org.id());

        assertThat(status(setActive(master, org.id(), "{\"active\":false}"))).isEqualTo(200);

        assertThat(fx.branchCount(org.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE organization_id = ?",
            Integer.class, org.id())).isEqualTo(members);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE organization_id = ? "
            + "AND is_active", Integer.class, org.id())).isEqualTo(members);
    }

    // RN-33: quien está dentro con la sesión abierta es rechazado en su siguiente solicitud.
    @Test
    void losUsuariosDelEstablecimientoDesactivadoPierdenAccesoYLoRecuperanAlReactivar() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        assertThat(mvc.perform(get("/branches").cookie(staff)).andReturn().getResponse().getStatus()).isEqualTo(200);

        setActive(master, org.id(), "{\"active\":false}");
        MvcResult blocked = mvc.perform(get("/branches").cookie(staff)).andReturn();
        assertThat(status(blocked)).isEqualTo(403);
        assertThat(body(blocked)).contains("ESTABLISHMENT_REQUIRED");

        setActive(master, org.id(), "{\"active\":true}");
        assertThat(mvc.perform(get("/branches").cookie(staff)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    // El Master sigue consultando un establecimiento inactivo, y la lista lo marca como inactivo.
    @Test
    void elMasterSigueVeyendoElEstablecimientoInactivo() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long orgId = fx.org(true);
        setActive(master, orgId, "{\"active\":false}");

        MvcResult r = mvc.perform(get("/establishments/" + orgId).cookie(master)).andReturn();
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"id\":" + orgId, "\"active\":false");
    }

    // Solo el Master: Admin, Staff, Entrenador y Cliente reciben 403 y nada cambia.
    @Test
    void ningunOtroRolPuedeCambiarElEstado() throws Exception {
        Org org = fx.orgWithAdmin();
        for (String role : new String[] {"staff", "trainer", "client"}) {
            Cookie cookie = fx.login(fx.actor(org.id(), role));
            assertThat(status(setActive(cookie, org.id(), "{\"active\":false}"))).as(role).isEqualTo(403);
        }
        assertThat(status(setActive(fx.login(org.admin()), org.id(), "{\"active\":false}"))).isEqualTo(403);
        assertThat(isActive(org.id())).isTrue();
        assertThat(events("ORGANIZATION_DEACTIVATED", org.id())).isZero();
    }

    // Sin sesión no se llega al endpoint.
    @Test
    void sinSesionEsRechazado() throws Exception {
        long orgId = fx.org(true);
        int s = mvc.perform(patch("/establishments/" + orgId + "/active").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andReturn()
            .getResponse().getStatus();
        assertThat(s).isIn(401, 403);
        assertThat(isActive(orgId)).isTrue();
    }

    // Un id que no existe da 404.
    @Test
    void establecimientoInexistenteDa404() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        assertThat(status(setActive(master, 999999L, "{\"active\":false}"))).isEqualTo(404);
    }

    // El cuerpo debe traer "active" booleano.
    @Test
    void elCampoActiveEsObligatorioYBooleano() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long orgId = fx.org(true);

        for (String json : new String[] {"{}", "{\"active\":\"no\"}", "{\"active\":null}", "{\"active\":0}"}) {
            MvcResult r = setActive(master, orgId, json);
            assertThat(status(r)).as(json).isEqualTo(400);
            assertThat(body(r)).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"active\"");
        }
        assertThat(isActive(orgId)).isTrue();
    }

    // No existe ninguna operación para eliminar un establecimiento.
    @Test
    void noSePuedeEliminarUnEstablecimiento() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long orgId = fx.org(true);

        int s = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/establishments/" + orgId).with(csrf()).cookie(master)).andReturn().getResponse().getStatus();

        assertThat(s).isIn(403, 404, 405);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organizations WHERE id = ?", Integer.class, orgId))
            .isEqualTo(1);
    }
}

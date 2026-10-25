package com.nexfit.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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

/** NEX-37 (US-19): gestionar planes de membresía. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PlanTests {

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

    private static String json(String name, String price, String days) {
        return "{\"name\":\"%s\",\"price\":%s,\"durationDays\":%s}".formatted(name, price, days);
    }

    private MvcResult create(Cookie session, String json) throws Exception {
        return mvc.perform(post("/membership-plans").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult modify(Cookie session, long id, String json) throws Exception {
        return mvc.perform(put("/membership-plans/" + id).with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult toggle(Cookie session, long id, boolean active) throws Exception {
        return mvc.perform(patch("/membership-plans/" + id + "/active").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":" + active + "}")).andReturn();
    }

    private MvcResult list(Cookie session, String query) throws Exception {
        return mvc.perform(get("/membership-plans" + query).cookie(session)).andReturn();
    }

    private MvcResult one(Cookie session, long id) throws Exception {
        return mvc.perform(get("/membership-plans/" + id).cookie(session)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static long idOf(String json) {
        return Long.parseLong(json.replaceAll("(?s).*?\"id\":(\\d+).*", "$1"));
    }

    private long plan(long orgId, String name, String price, int days, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, ?, CAST(? AS numeric), ?, ?) RETURNING id
            """, Long.class, orgId, name, price, days, active);
    }

    private int planCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM membership_plans WHERE organization_id = ?",
            Integer.class, orgId);
    }

    /** Contrata el plan para un cliente nuevo del gimnasio; devuelve el id de la membresía. */
    private long contract(Org org, long planId) {
        Account client = fx.user(false, true);
        long memberId = fx.member(client, org.id(), "client", true);
        return jdbc.queryForObject("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id,
                                     start_date, end_date, status)
            VALUES (?, ?, ?, ?, DATE '2026-10-01', DATE '2026-10-31', 'active') RETURNING id
            """, Long.class, org.id(), memberId, planId, org.adminMemberId());
    }

    private Map<String, Object> membership(long id) {
        return jdbc.queryForMap(
            "SELECT plan_id, start_date, end_date, CAST(status AS text) AS status FROM memberships WHERE id = ?", id);
    }

    private void assertRejected(MvcResult r, String field) throws Exception {
        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r)).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"" + field + "\"");
    }

    // ---- criterio 1 ----------------------------------------------------------------------

    // El Administrador crea un plan con nombre, precio ≥ 0 y duración > 0; queda disponible en su gimnasio.
    @Test
    void elAdministradorCreaUnPlanActivoYQuedaDisponibleEnSuGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());

        MvcResult r = create(admin, "{\"name\":\"  Mensual \",\"description\":\"Acceso libre\","
            + "\"price\":50000,\"durationDays\":30,\"benefits\":\"Casillero\"}");
        assertThat(status(r)).isEqualTo(201);
        long id = idOf(body(r));

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT organization_id, name, description, price, duration_days, benefits, is_active
            FROM membership_plans WHERE id = ?
            """, id);
        assertThat(row).containsEntry("organization_id", org.id()).containsEntry("name", "Mensual")
            .containsEntry("description", "Acceso libre").containsEntry("duration_days", 30)
            .containsEntry("benefits", "Casillero").containsEntry("is_active", true);
        assertThat((BigDecimal) row.get("price")).isEqualByComparingTo("50000");

        for (String role : new String[] {"staff", "client"}) {
            MvcResult listed = list(fx.login(fx.actor(org.id(), role)), "");
            assertThat(status(listed)).as(role).isEqualTo(200);
            assertThat(body(listed)).as(role).contains("\"id\":" + id).contains("\"name\":\"Mensual\"");
        }
    }

    // Precio 0 es válido (≥ 0); descripción y beneficios son opcionales.
    @Test
    void unPlanGratuitoYSinDescripcionEsValido() throws Exception {
        Org org = fx.orgWithAdmin();

        MvcResult r = create(fx.login(org.admin()), json("Prueba", "0", "7"));

        assertThat(status(r)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT price FROM membership_plans WHERE id = ?", BigDecimal.class,
            idOf(body(r)))).isEqualByComparingTo("0");
    }

    // ---- criterio 2 ----------------------------------------------------------------------

    // Precio negativo, duración 0 (u otros datos inválidos) se rechazan indicando el campo; no se crea nada.
    @Test
    void precioNegativoDuracionCeroOCamposInvalidosSeRechazanIndicandoElCampo() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());

        assertRejected(create(admin, json("Plan", "-1", "30")), "price");
        assertRejected(create(admin, json("Plan", "10.555", "30")), "price");
        assertRejected(create(admin, json("Plan", "\"abc\"", "30")), "price");
        assertRejected(create(admin, "{\"name\":\"Plan\",\"durationDays\":30}"), "price");
        assertRejected(create(admin, json("Plan", "1000", "0")), "durationDays");
        assertRejected(create(admin, json("Plan", "1000", "-5")), "durationDays");
        assertRejected(create(admin, json("Plan", "1000", "1.5")), "durationDays");
        assertRejected(create(admin, json("Plan", "1000", "99999")), "durationDays");
        assertRejected(create(admin, "{\"name\":\"Plan\",\"price\":1000}"), "durationDays");
        assertRejected(create(admin, "{\"price\":1000,\"durationDays\":30}"), "name");
        assertRejected(create(admin, json("   ", "1000", "30")), "name");
        assertRejected(create(admin, json("x".repeat(101), "1000", "30")), "name");

        assertThat(planCount(org.id())).isZero();
    }

    // Lo mismo al modificar: el plan queda como estaba.
    @Test
    void modificarConDatosInvalidosSeRechazaYElPlanNoCambia() throws Exception {
        Org org = fx.orgWithAdmin();
        long id = plan(org.id(), "Mensual", "50000", 30, true);
        Cookie admin = fx.login(org.admin());

        assertRejected(modify(admin, id, json("Mensual", "-1", "30")), "price");
        assertRejected(modify(admin, id, json("Mensual", "50000", "0")), "durationDays");

        Map<String, Object> row = jdbc.queryForMap("SELECT price, duration_days FROM membership_plans WHERE id = ?", id);
        assertThat((BigDecimal) row.get("price")).isEqualByComparingTo("50000");
        assertThat(row).containsEntry("duration_days", 30);
    }

    // Nombre repetido en el mismo gimnasio (sin distinguir mayúsculas) → 409; otro gimnasio puede reutilizarlo.
    @Test
    void nombreRepetidoEnElMismoGimnasioSeRechazaSinImportarMayusculasYOtroGimnasioPuedeReutilizarlo() throws Exception {
        Org a = fx.orgWithAdmin();
        Org b = fx.orgWithAdmin();
        Cookie adminA = fx.login(a.admin());
        assertThat(status(create(adminA, json("Mensual", "50000", "30")))).isEqualTo(201);

        for (String repeated : new String[] {"Mensual", "mensual", "MENSUAL", " MeNsUaL "}) {
            MvcResult r = create(adminA, json(repeated, "1000", "10"));
            assertThat(status(r)).as(repeated).isEqualTo(409);
            assertThat(body(r)).contains("\"code\":\"PLAN_NAME_TAKEN\"", "\"field\":\"name\"");
        }
        assertThat(planCount(a.id())).isEqualTo(1);

        assertThat(status(create(fx.login(b.admin()), json("Mensual", "1000", "10")))).isEqualTo(201);
        assertThat(planCount(b.id())).isEqualTo(1);
    }

    // Al modificar tampoco se puede tomar el nombre de otro plan; conservar el propio (aunque cambie el
    // uso de mayúsculas) sí es válido.
    @Test
    void modificarNoPermiteTomarElNombreDeOtroPlanPeroSiConservarElPropio() throws Exception {
        Org org = fx.orgWithAdmin();
        long mensual = plan(org.id(), "Mensual", "50000", 30, true);
        plan(org.id(), "Anual", "500000", 365, true);
        Cookie admin = fx.login(org.admin());

        MvcResult taken = modify(admin, mensual, json("anual", "50000", "30"));
        assertThat(status(taken)).isEqualTo(409);
        assertThat(body(taken)).contains("PLAN_NAME_TAKEN");

        assertThat(status(modify(admin, mensual, json("MENSUAL", "60000", "30")))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT name FROM membership_plans WHERE id = ?", String.class, mensual))
            .isEqualTo("MENSUAL");
    }

    // ---- criterio 3 ----------------------------------------------------------------------

    // Cambiar precio o duración, o desactivar un plan con membresías contratadas, no altera esas membresías.
    @Test
    void cambiarOdesactivarUnPlanNoAlteraLasMembresiasYaContratadas() throws Exception {
        Org org = fx.orgWithAdmin();
        long planId = plan(org.id(), "Mensual", "50000", 30, true);
        long membershipId = contract(org, planId);
        Map<String, Object> before = membership(membershipId);
        Cookie admin = fx.login(org.admin());

        assertThat(status(modify(admin, planId, json("Mensual", "99000", "90")))).isEqualTo(200);
        assertThat(status(toggle(admin, planId, false))).isEqualTo(200);

        Map<String, Object> plan = jdbc.queryForMap(
            "SELECT price, duration_days, is_active FROM membership_plans WHERE id = ?", planId);
        assertThat((BigDecimal) plan.get("price")).isEqualByComparingTo("99000");
        assertThat(plan).containsEntry("duration_days", 90).containsEntry("is_active", false);
        assertThat(membership(membershipId)).isEqualTo(before);
        assertThat(before).containsEntry("status", "active");
    }

    // ---- criterio 4 ----------------------------------------------------------------------

    // El plan inactivo queda marcado como no disponible: Staff y Cliente ya no lo ven (la verificación al
    // contratar, RN-33, se hace en NEX-49 sobre este mismo estado). El Administrador sigue viéndolo.
    @Test
    void unPlanInactivoDejaDeSerVisibleParaStaffYClienteYSePuedeReactivar() throws Exception {
        Org org = fx.orgWithAdmin();
        long activo = plan(org.id(), "Mensual", "50000", 30, true);
        long inactivo = plan(org.id(), "Trimestral", "130000", 90, true);
        Cookie admin = fx.login(org.admin());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Cookie client = fx.login(fx.actor(org.id(), "client"));

        MvcResult off = toggle(admin, inactivo, false);
        assertThat(status(off)).isEqualTo(200);
        assertThat(body(off)).contains("\"active\":false");
        assertThat(jdbc.queryForObject("SELECT is_active FROM membership_plans WHERE id = ?", Boolean.class,
            inactivo)).isFalse();

        for (Cookie session : new Cookie[] {staff, client}) {
            MvcResult listed = list(session, "");
            assertThat(body(listed)).contains("\"id\":" + activo).doesNotContain("\"id\":" + inactivo);
            // Pedir los inactivos explícitamente no cambia lo que ven.
            assertThat(body(list(session, "?active=false"))).doesNotContain("\"id\":" + inactivo);
            assertThat(status(one(session, inactivo))).isEqualTo(404);
            assertThat(status(one(session, activo))).isEqualTo(200);
        }

        assertThat(body(list(admin, ""))).contains("\"id\":" + activo, "\"id\":" + inactivo);
        assertThat(body(list(admin, "?active=false"))).contains("\"id\":" + inactivo)
            .doesNotContain("\"id\":" + activo);
        assertThat(body(list(admin, "?active=true"))).contains("\"id\":" + activo)
            .doesNotContain("\"id\":" + inactivo);
        assertThat(status(one(admin, inactivo))).isEqualTo(200);

        assertThat(status(toggle(admin, inactivo, true))).isEqualTo(200);
        assertThat(body(list(staff, ""))).contains("\"id\":" + inactivo);
    }

    @Test
    void activarODesactivarExigeUnBooleanYEsIdempotente() throws Exception {
        Org org = fx.orgWithAdmin();
        long id = plan(org.id(), "Mensual", "50000", 30, true);
        Cookie admin = fx.login(org.admin());

        MvcResult bad = mvc.perform(patch("/membership-plans/" + id + "/active").with(csrf()).cookie(admin)
            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":\"no\"}")).andReturn();
        assertRejected(bad, "active");

        assertThat(status(toggle(admin, id, true))).isEqualTo(200);
        assertThat(status(toggle(admin, id, false))).isEqualTo(200);
        assertThat(status(toggle(admin, id, false))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT is_active FROM membership_plans WHERE id = ?", Boolean.class, id))
            .isFalse();
    }

    // ---- criterio 5 ----------------------------------------------------------------------

    // Staff y Cliente listan los planes de su gimnasio pero no pueden crear, modificar ni activar (403).
    @Test
    @ReglaDeNegocio("RN-06")
    void staffYClienteConsultanPeroNoPuedenModificarPlanes() throws Exception {
        Org org = fx.orgWithAdmin();
        long id = plan(org.id(), "Mensual", "50000", 30, true);

        for (String role : new String[] {"staff", "client"}) {
            Cookie session = fx.login(fx.actor(org.id(), role));

            assertThat(status(list(session, ""))).as(role).isEqualTo(200);
            assertThat(status(one(session, id))).as(role).isEqualTo(200);

            MvcResult created = create(session, json("Nuevo " + role, "1000", "10"));
            assertThat(status(created)).as(role).isEqualTo(403);
            assertThat(body(created)).contains("ACCESS_DENIED");
            assertThat(status(modify(session, id, json("Mensual", "1", "1")))).as(role).isEqualTo(403);
            assertThat(status(toggle(session, id, false))).as(role).isEqualTo(403);
        }

        assertThat(planCount(org.id())).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT price, duration_days, is_active FROM membership_plans WHERE id = ?", id);
        assertThat((BigDecimal) row.get("price")).isEqualByComparingTo("50000");
        assertThat(row).containsEntry("duration_days", 30).containsEntry("is_active", true);
    }

    // El Entrenador no figura en la matriz de planes: 403 incluso al consultar. Sin sesión: 401.
    @Test
    @ReglaDeNegocio("RN-06")
    void elEntrenadorNoAccedeAPlanesYSinSesionSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        long id = plan(org.id(), "Mensual", "50000", 30, true);
        Cookie trainer = fx.login(fx.actor(org.id(), "trainer"));

        assertThat(status(list(trainer, ""))).isEqualTo(403);
        assertThat(status(one(trainer, id))).isEqualTo(403);
        assertThat(status(create(trainer, json("Nuevo", "1000", "10")))).isEqualTo(403);

        assertThat(status(mvc.perform(get("/membership-plans")).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(post("/membership-plans").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json("Nuevo", "1000", "10"))).andReturn())).isEqualTo(401);
    }

    // ---- aislamiento entre gimnasios (RN-20) ---------------------------------------------

    // Un plan de otro gimnasio responde 404, igual que un id inexistente, y no se modifica.
    @Test
    @ReglaDeNegocio("RN-03")
    void unPlanDeOtroGimnasioDa404IdenticoAUnIdInexistenteYNoSeModifica() throws Exception {
        Org a = fx.orgWithAdmin();
        Org b = fx.orgWithAdmin();
        long ownPlan = plan(a.id(), "Mensual A", "50000", 30, true);
        long otherPlan = plan(b.id(), "Mensual B", "70000", 30, true);
        long missing = otherPlan + 10_000;
        Cookie adminA = fx.login(a.admin());

        assertThat(status(one(adminA, ownPlan))).isEqualTo(200);
        MvcResult foreign = one(adminA, otherPlan);
        MvcResult nonexistent = one(adminA, missing);
        assertThat(status(foreign)).isEqualTo(404);
        assertThat(status(nonexistent)).isEqualTo(404);
        assertThat(body(foreign)).isEqualTo(body(nonexistent));

        assertThat(status(modify(adminA, otherPlan, json("Hackeado", "1", "1")))).isEqualTo(404);
        assertThat(status(toggle(adminA, otherPlan, false))).isEqualTo(404);
        assertThat(status(modify(adminA, missing, json("Hackeado", "1", "1")))).isEqualTo(404);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT name, price, is_active FROM membership_plans WHERE id = ?", otherPlan);
        assertThat(row).containsEntry("name", "Mensual B").containsEntry("is_active", true);
        assertThat((BigDecimal) row.get("price")).isEqualByComparingTo("70000");

        String listed = body(list(adminA, ""));
        assertThat(listed).contains("Mensual A").doesNotContain("Mensual B");
    }

    // Un gimnasio nuevo no tiene planes; la lista vacía no es un error.
    @Test
    void unGimnasioSinPlanesDevuelveListaVacia() throws Exception {
        Org org = fx.orgWithAdmin();

        MvcResult r = list(fx.login(org.admin()), "");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).isEqualTo("[]");
    }
}

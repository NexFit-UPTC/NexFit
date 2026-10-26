package com.nexfit.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
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

/** NEX-35 (US-10): gestionar sedes. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class BranchTests {

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

    private static String json(String name) {
        return "{\"name\":\"%s\",\"addressLine1\":\"Calle 10 # 5-20\",\"city\":\"Tunja\",\"department\":\"Boyacá\"}"
            .formatted(name);
    }

    private MvcResult create(Cookie session, String json) throws Exception {
        return mvc.perform(post("/branches").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult modify(Cookie session, long id, String json) throws Exception {
        return mvc.perform(put("/branches/" + id).with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult toggle(Cookie session, long id, boolean active) throws Exception {
        return mvc.perform(patch("/branches/" + id + "/active").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":" + active + "}")).andReturn();
    }

    private MvcResult list(Cookie session, String query) throws Exception {
        return mvc.perform(get("/branches" + query).cookie(session)).andReturn();
    }

    private MvcResult one(Cookie session, long id) throws Exception {
        return mvc.perform(get("/branches/" + id).cookie(session)).andReturn();
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

    private long branch(long orgId, String name, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO branches (organization_id, name, address_line1, city, department, country, is_active)
            VALUES (?, ?, 'Calle 1', 'Tunja', 'Boyacá', 'Colombia', ?) RETURNING id
            """, Long.class, orgId, name, active);
    }

    private int branchCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM branches WHERE organization_id = ?", Integer.class, orgId);
    }

    private void assertRejected(MvcResult r, String field) throws Exception {
        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r)).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"" + field + "\"");
    }

    // ---- criterio 1 ----------------------------------------------------------------------

    // El Administrador crea una sede con nombre, dirección, ciudad y departamento; queda activa en su gimnasio.
    @Test
    void elAdministradorCreaUnaSedeActivaEnSuGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());

        MvcResult r = create(admin, "{\"name\":\"  Sede Norte \",\"addressLine1\":\"Calle 10 # 5-20\","
            + "\"city\":\"Tunja\",\"department\":\"Boyacá\",\"phone\":\"3001234567\",\"schedule\":\"L-V 6am-9pm\"}");

        assertThat(status(r)).isEqualTo(201);
        long id = idOf(body(r));
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT organization_id, name, address_line1, city, department, country, phone, schedule, is_active
              FROM branches WHERE id = ?""", id);
        assertThat(row).containsEntry("organization_id", org.id()).containsEntry("name", "Sede Norte")
            .containsEntry("address_line1", "Calle 10 # 5-20").containsEntry("city", "Tunja")
            .containsEntry("department", "Boyacá").containsEntry("country", "Colombia")
            .containsEntry("phone", "3001234567").containsEntry("schedule", "L-V 6am-9pm")
            .containsEntry("is_active", true);

        assertThat(body(list(admin, ""))).contains("Sede Norte");
        assertThat(status(one(admin, id))).isEqualTo(200);
    }

    // Los campos obligatorios faltantes o fuera de rango se rechazan indicando el campo; no se crea nada.
    @Test
    void camposObligatoriosFaltantesOExcedidosSeRechazanIndicandoElCampo() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());

        assertRejected(create(admin, "{\"addressLine1\":\"Calle 1\",\"city\":\"Tunja\",\"department\":\"Boyacá\"}"), "name");
        assertRejected(create(admin, json("   ")), "name");
        assertRejected(create(admin, json("x".repeat(151))), "name");
        assertRejected(create(admin, "{\"name\":\"A\",\"city\":\"Tunja\",\"department\":\"Boyacá\"}"), "addressLine1");
        assertRejected(create(admin, "{\"name\":\"A\",\"addressLine1\":\"Calle 1\",\"department\":\"Boyacá\"}"), "city");
        assertRejected(create(admin, "{\"name\":\"A\",\"addressLine1\":\"Calle 1\",\"city\":\"Tunja\"}"), "department");
        assertRejected(create(admin, "{\"name\":123,\"addressLine1\":\"Calle 1\",\"city\":\"Tunja\",\"department\":\"B\"}"), "name");
        assertRejected(create(admin, "{\"name\":\"A\",\"addressLine1\":\"Calle 1\",\"city\":\"Tunja\","
            + "\"department\":\"B\",\"phone\":\"" + "1".repeat(31) + "\"}"), "phone");

        assertThat(branchCount(org.id())).isZero();
    }

    // ---- criterio 2 ----------------------------------------------------------------------

    // Nombre repetido en el mismo gimnasio (sin distinguir mayúsculas): 409; otro gimnasio puede reutilizarlo.
    @Test
    void nombreRepetidoEnElMismoGimnasioSeRechazaYOtroGimnasioPuedeReutilizarlo() throws Exception {
        Org a = fx.orgWithAdmin();
        Org b = fx.orgWithAdmin();
        Cookie adminA = fx.login(a.admin());
        Cookie adminB = fx.login(b.admin());
        assertThat(status(create(adminA, json("Sede Centro")))).isEqualTo(201);

        for (String repeated : new String[] {"Sede Centro", "SEDE CENTRO", "sede centro", " Sede Centro "}) {
            MvcResult r = create(adminA, json(repeated));
            assertThat(status(r)).as(repeated).isEqualTo(409);
            assertThat(body(r)).contains("\"code\":\"BRANCH_NAME_TAKEN\"", "\"field\":\"name\"");
        }
        assertThat(branchCount(a.id())).isEqualTo(1);

        assertThat(status(create(adminB, json("Sede Centro")))).isEqualTo(201);
        assertThat(branchCount(b.id())).isEqualTo(1);
    }

    // Modificar no permite tomar el nombre de otra sede, pero sí conservar el propio y cambiar el resto.
    @Test
    void modificarNoPermiteTomarElNombreDeOtraSedePeroSiConservarElPropio() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long centro = branch(org.id(), "Centro", true);
        branch(org.id(), "Norte", true);

        assertThat(status(modify(admin, centro, json("norte")))).isEqualTo(409);

        MvcResult ok = modify(admin, centro, json("CENTRO"));
        assertThat(status(ok)).isEqualTo(200);
        assertThat(body(ok)).contains("\"name\":\"CENTRO\"", "\"city\":\"Tunja\"", "\"addressLine1\":\"Calle 10 # 5-20\"");
        assertThat(jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, centro)).isEqualTo("CENTRO");
    }

    // Modificar con datos inválidos se rechaza y la sede no cambia.
    @Test
    void modificarConDatosInvalidosSeRechazaYLaSedeNoCambia() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long id = branch(org.id(), "Centro", true);

        assertRejected(modify(admin, id, "{\"name\":\"Centro\",\"addressLine1\":\"Calle 1\",\"city\":\"Tunja\"}"), "department");

        assertThat(jdbc.queryForObject("SELECT address_line1 FROM branches WHERE id = ?", String.class, id))
            .isEqualTo("Calle 1");
    }

    // ---- criterio 3 ----------------------------------------------------------------------

    // Desactivar una sede solo cambia su estado: sus datos se conservan y se puede reactivar (idempotente).
    @Test
    void desactivarUnaSedeConservaSusDatosYSePuedeReactivar() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long id = branch(org.id(), "Centro", true);
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        assertThat(status(toggle(admin, id, false))).isEqualTo(200);
        assertThat(status(toggle(admin, id, false))).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap("SELECT name, city, is_active FROM branches WHERE id = ?", id);
        assertThat(row).containsEntry("name", "Centro").containsEntry("city", "Tunja").containsEntry("is_active", false);

        // El Administrador la sigue viendo (y puede filtrar); Staff ya no.
        assertThat(body(list(admin, "?active=false"))).contains("Centro");
        assertThat(body(list(admin, "?active=true"))).doesNotContain("Centro");
        assertThat(status(one(admin, id))).isEqualTo(200);
        assertThat(body(list(staff, ""))).doesNotContain("Centro");
        assertThat(status(one(staff, id))).isEqualTo(404);

        assertThat(status(toggle(admin, id, true))).isEqualTo(200);
        assertThat(body(list(staff, ""))).contains("Centro");
    }

    // El campo active debe ser un booleano.
    @Test
    void activarODesactivarExigeUnBooleano() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long id = branch(org.id(), "Centro", true);

        MvcResult r = mvc.perform(patch("/branches/" + id + "/active").with(csrf()).cookie(admin)
            .contentType(MediaType.APPLICATION_JSON).content("{\"active\":\"no\"}")).andReturn();

        assertRejected(r, "active");
        assertThat(jdbc.queryForObject("SELECT is_active FROM branches WHERE id = ?", Boolean.class, id)).isTrue();
    }

    // ---- criterio 4 ----------------------------------------------------------------------

    // Staff, Entrenador y Cliente listan las sedes de su gimnasio pero no pueden crear, modificar ni activar (403).
    @Test
    @ReglaDeNegocio("RN-06")
    void staffEntrenadorYClienteConsultanPeroNoPuedenModificarSedes() throws Exception {
        Org org = fx.orgWithAdmin();
        long id = branch(org.id(), "Centro", true);

        for (String role : new String[] {"staff", "trainer", "client"}) {
            Cookie session = fx.login(fx.actor(org.id(), role));

            assertThat(status(list(session, ""))).as(role).isEqualTo(200);
            assertThat(body(list(session, ""))).as(role).contains("Centro");
            assertThat(status(one(session, id))).as(role).isEqualTo(200);

            MvcResult created = create(session, json("Nueva " + role));
            assertThat(status(created)).as(role).isEqualTo(403);
            assertThat(body(created)).contains("ACCESS_DENIED");
            assertThat(status(modify(session, id, json("Hackeada")))).as(role).isEqualTo(403);
            assertThat(status(toggle(session, id, false))).as(role).isEqualTo(403);
        }

        assertThat(branchCount(org.id())).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT name, is_active FROM branches WHERE id = ?", id);
        assertThat(row).containsEntry("name", "Centro").containsEntry("is_active", true);
    }

    // Sin sesión: 401.
    @Test
    void sinSesionSeRechaza() throws Exception {
        assertThat(status(mvc.perform(get("/branches")).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(post("/branches").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json("Nueva"))).andReturn())).isEqualTo(401);
    }

    // Una sede de otro gimnasio responde 404, igual que un id inexistente, y no se modifica; la lista solo trae las propias.
    @Test
    @ReglaDeNegocio("RN-03")
    void unaSedeDeOtroGimnasioDa404IdenticoAUnIdInexistenteYNoSeModifica() throws Exception {
        Org a = fx.orgWithAdmin();
        Org b = fx.orgWithAdmin();
        Cookie adminA = fx.login(a.admin());
        branch(a.id(), "Sede A", true);
        long other = branch(b.id(), "Sede B", true);
        long missing = other + 100_000;

        MvcResult foreign = one(adminA, other);
        MvcResult nonexistent = one(adminA, missing);
        assertThat(status(foreign)).isEqualTo(404);
        assertThat(status(nonexistent)).isEqualTo(404);
        assertThat(body(foreign)).isEqualTo(body(nonexistent));

        assertThat(status(modify(adminA, other, json("Hackeada")))).isEqualTo(404);
        assertThat(status(toggle(adminA, other, false))).isEqualTo(404);
        assertThat(status(modify(adminA, missing, json("Hackeada")))).isEqualTo(404);

        Map<String, Object> row = jdbc.queryForMap("SELECT name, is_active FROM branches WHERE id = ?", other);
        assertThat(row).containsEntry("name", "Sede B").containsEntry("is_active", true);

        String listed = body(list(adminA, ""));
        assertThat(listed).contains("Sede A").doesNotContain("Sede B");
    }

    // Un gimnasio sin sedes devuelve una lista vacía, no un error.
    @Test
    void unGimnasioSinSedesDevuelveListaVacia() throws Exception {
        Org org = fx.orgWithAdmin();

        MvcResult r = list(fx.login(org.admin()), "");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).isEqualTo("[]");
    }
}

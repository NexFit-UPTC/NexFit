package com.nexfit.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.nexfit.shared.AccessFixtures;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** NEX-97: listado y detalle público de gimnasios, sin sesión. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PublicGymTests {

    private static final String SECRET_EMAIL = "secreto-contacto@nexfit.test";

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

    private long gym(String name, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, contact_phone, timezone, is_active)
            VALUES (?, ?, '3001234567', 'America/Bogota', ?) RETURNING id
            """, Long.class, name, SECRET_EMAIL, active);
    }

    private void branch(long orgId, String name, boolean active) {
        jdbc.update("""
            INSERT INTO branches (organization_id, name, phone, schedule, address_line1, city, department, country,
                                  is_active)
            VALUES (?, ?, '3109998888', 'L-V 6 a 20', 'Calle 10 # 5-20', 'Tunja', 'Boyacá', 'Colombia', ?)
            """, orgId, name, active);
    }

    private void plan(long orgId, String name, boolean active) {
        jdbc.update("""
            INSERT INTO membership_plans (organization_id, name, description, price, duration_days, benefits,
                                          is_active)
            VALUES (?, ?, 'descripción interna', 90000, 30, 'beneficios internos', ?)
            """, orgId, name, active);
    }

    private MvcResult list(String query) throws Exception {
        return mvc.perform(get("/public/gyms" + query)).andReturn();
    }

    private MvcResult one(long id) throws Exception {
        return mvc.perform(get("/public/gyms/" + id)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Set<String> keys(String json, String path) {
        Object found = JsonPath.read(json, path);
        while (found instanceof List<?> list) {
            found = list.get(0);
        }
        return ((Map<String, Object>) found).keySet();
    }

    // ---- criterio 1: listado paginado de gimnasios activos con datos públicos -------------

    @Test
    void elListadoSinSesionTraeSoloGimnasiosActivosConSusSedesActivas() throws Exception {
        long visible = gym("Lista Visible", true);
        branch(visible, "Sede visible", true);
        branch(visible, "Sede cerrada", false);
        gym("Lista Inactivo", false);

        MvcResult r = list("?size=50");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String json = body(r);
        List<String> names = JsonPath.read(json, "$.content[*].name");
        assertThat(names).contains("Lista Visible").doesNotContain("Lista Inactivo");
        List<Map<String, Object>> content = JsonPath.read(json, "$.content");
        Map<String, Object> mine = content.stream().filter(g -> "Lista Visible".equals(g.get("name"))).findFirst()
            .orElseThrow();
        assertThat(mine.keySet()).containsExactlyInAnyOrder("id", "name", "branches");
        List<Map<String, Object>> branches = (List<Map<String, Object>>) mine.get("branches");
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).get("name")).isEqualTo("Sede visible");
        assertThat(branches.get(0).keySet()).containsExactlyInAnyOrder("name", "addressLine1", "addressLine2",
            "city", "department");
    }

    @Test
    void elTamanoDePaginaEstaAcotadoYLaPaginacionFunciona() throws Exception {
        for (int i = 0; i < 3; i++) {
            gym("Pagina " + i, true);
        }

        MvcResult huge = list("?size=1000");
        MvcResult first = list("?page=0&size=2");
        MvcResult second = list("?page=1&size=2");

        assertThat((Integer) JsonPath.read(body(huge), "$.size")).isEqualTo(50);
        String firstJson = body(first);
        assertThat((Integer) JsonPath.read(firstJson, "$.size")).isEqualTo(2);
        assertThat((List<?>) JsonPath.read(firstJson, "$.content")).hasSize(2);
        long total = ((Number) JsonPath.read(firstJson, "$.totalElements")).longValue();
        assertThat(total).isGreaterThanOrEqualTo(3);
        assertThat((Integer) JsonPath.read(firstJson, "$.totalPages")).isEqualTo((int) ((total + 1) / 2));
        assertThat((Integer) JsonPath.read(body(second), "$.page")).isEqualTo(1);
        List<Integer> firstIds = JsonPath.read(firstJson, "$.content[*].id");
        List<Integer> secondIds = JsonPath.read(body(second), "$.content[*].id");
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
    }

    // ---- criterio 2: detalle con sedes y planes activos; 404 si no existe o está inactivo -------

    @Test
    void elDetalleTraeSedesYPlanesActivosConNombrePrecioYDuracion() throws Exception {
        long id = gym("Detalle Visible", true);
        branch(id, "Sede activa", true);
        branch(id, "Sede inactiva", false);
        plan(id, "Plan activo", true);
        plan(id, "Plan inactivo", false);

        MvcResult r = one(id);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String json = body(r);
        assertThat(keys(json, "$")).containsExactlyInAnyOrder("id", "name", "branches", "plans");
        assertThat((String) JsonPath.read(json, "$.name")).isEqualTo("Detalle Visible");
        assertThat((List<String>) JsonPath.read(json, "$.branches[*].name")).containsExactly("Sede activa");
        assertThat((List<String>) JsonPath.read(json, "$.plans[*].name")).containsExactly("Plan activo");
        assertThat(keys(json, "$.plans[0]")).containsExactlyInAnyOrder("name", "price", "durationDays");
        assertThat(((Number) JsonPath.read(json, "$.plans[0].price")).doubleValue()).isEqualTo(90000.0);
        assertThat((Integer) JsonPath.read(json, "$.plans[0].durationDays")).isEqualTo(30);
    }

    @Test
    void unGimnasioInexistenteOInactivoResponde404() throws Exception {
        long inactive = gym("Detalle Inactivo", false);

        MvcResult off = one(inactive);
        MvcResult missing = one(inactive + 1_000_000);

        assertThat(off.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(off)).isEqualTo(body(missing));
    }

    // ---- criterio 3: nada de contacto, conteos, personal ni datos personales --------------------

    @Test
    void laRespuestaNoIncluyeContactoConteosNiPersonal() throws Exception {
        long id = gym("Sin Fugas", true);
        branch(id, "Sede sin fugas", true);
        plan(id, "Plan sin fugas", true);
        var admin = fx.actor(id, "admin");
        fx.actor(id, "client");

        String detail = body(one(id));
        String all = body(list("?size=50"));

        for (String json : List.of(detail, all)) {
            assertThat(json).doesNotContain(SECRET_EMAIL, admin.email(), "contactEmail", "contactPhone", "3001234567",
                "3109998888", "L-V 6 a 20", "descripción interna", "beneficios internos", "clientCount",
                "branchCount", "timezone", "staff", "members");
        }
    }

    // ---- criterio 4: el resto sigue con default-deny; la excepción es solo GET y solo estas rutas ----

    @Test
    void sinSesionLasDemasRutasSiguenEn401YLaExcepcionEsSoloGet() throws Exception {
        long id = gym("Deny", true);

        assertThat(mvc.perform(get("/branches")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/establishments")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/public/gyms/" + id + "/plans")).andReturn().getResponse().getStatus())
            .isEqualTo(401);
        assertThat(mvc.perform(get("/public/otra")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/public/gyms").with(csrf())).andReturn().getResponse().getStatus())
            .isEqualTo(401);
        assertThat(mvc.perform(post("/public/gyms/" + id).with(csrf())).andReturn().getResponse().getStatus())
            .isEqualTo(401);
    }

    // ---- criterio 5 (SessionUserFilter): una cookie vencida no afecta las rutas públicas ----------

    @Test
    void unaCookieDeSesionVencidaSigueVeiendoLasRutasPublicas() throws Exception {
        var org = fx.orgWithAdmin();
        Cookie session = fx.login(org.admin());
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", org.admin().id());

        int privateRoute = mvc.perform(get("/branches").cookie(session)).andReturn().getResponse().getStatus();
        int publicList = mvc.perform(get("/public/gyms").cookie(session)).andReturn().getResponse().getStatus();
        int publicDetail = mvc.perform(get("/public/gyms/" + org.id()).cookie(session)).andReturn().getResponse()
            .getStatus();

        assertThat(privateRoute).as("control: la misma cookie sí falla en una ruta privada").isEqualTo(401);
        assertThat(publicList).isEqualTo(200);
        assertThat(publicDetail).isEqualTo(200);
    }
}

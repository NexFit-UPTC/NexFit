package com.nexfit.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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

/** NEX-38 (US-12): inventario de equipo por sede. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentTests {

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

    private MvcResult send(Cookie session, String method, String uri, String json) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(uri);
            case "PUT" -> put(uri);
            case "PATCH" -> patch(uri);
            default -> get(uri);
        };
        if (!method.equals("GET")) {
            builder = builder.with(csrf()).contentType(MediaType.APPLICATION_JSON);
            if (json != null) {
                builder = builder.content(json);
            }
        }
        return mvc.perform(builder.cookie(session)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static long idOf(String json) {
        return Long.parseLong(json.replaceAll("(?s)^\\{\"id\":(\\d+).*", "$1"));
    }

    private String create(Cookie admin, long branch, String name, String extra) throws Exception {
        MvcResult r = send(admin, "POST", "/equipment",
            "{\"branchId\":%d,\"name\":\"%s\"%s}".formatted(branch, name, extra));
        assertThat(status(r)).as(body(r)).isEqualTo(201);
        return body(r);
    }

    @Test
    void elAdministradorRegistraUnEquipoEnUnaSedeDeSuGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        long branch = fx.branch(org.id());
        Cookie admin = fx.login(org.admin());

        MvcResult r = send(admin, "POST", "/equipment", ("{\"branchId\":%d,\"name\":\"Prensa de piernas\","
            + "\"category\":\"Fuerza\",\"acquisitionDate\":\"2025-03-10\",\"imageUrl\":\"/img/equipos/prensa.svg\"}")
            .formatted(branch));

        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"name\":\"Prensa de piernas\"").contains("\"category\":\"Fuerza\"")
            .contains("\"status\":\"available\"").contains("\"acquisitionDate\":\"2025-03-10\"")
            .contains("\"imageUrl\":\"/img/equipos/prensa.svg\"").contains("\"branchId\":" + branch);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment WHERE organization_id = ?", Integer.class,
            org.id())).isEqualTo(1);
    }

    @Test
    void unaSedeDeOtroGimnasioOInactivaSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        long foreign = fx.branch(other.id());
        long inactive = fx.branch(org.id());
        jdbc.update("UPDATE branches SET is_active = false WHERE id = ?", inactive);
        Cookie admin = fx.login(org.admin());

        MvcResult a = send(admin, "POST", "/equipment", "{\"branchId\":%d,\"name\":\"Cinta\"}".formatted(foreign));
        MvcResult b = send(admin, "POST", "/equipment", "{\"branchId\":%d,\"name\":\"Cinta\"}".formatted(inactive));

        assertThat(status(a)).isEqualTo(400);
        assertThat(body(a)).contains("VALIDATION_ERROR").contains("branchId");
        assertThat(status(b)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment", Integer.class)).isZero();
    }

    @Test
    void validaNombreEstadoFechaEImagen() throws Exception {
        Org org = fx.orgWithAdmin();
        long branch = fx.branch(org.id());
        Cookie admin = fx.login(org.admin());
        String b = "\"branchId\":" + branch;

        assertThat(status(send(admin, "POST", "/equipment", "{" + b + "}"))).isEqualTo(400);
        assertThat(status(send(admin, "POST", "/equipment", "{" + b + ",\"name\":\"  \"}"))).isEqualTo(400);
        assertThat(status(send(admin, "POST", "/equipment", "{\"name\":\"Cinta\"}"))).isEqualTo(400);
        assertThat(status(send(admin, "POST", "/equipment", "{" + b + ",\"name\":\"X\",\"status\":\"roto\"}")))
            .isEqualTo(400);
        assertThat(status(send(admin, "POST", "/equipment", "{" + b + ",\"name\":\"X\",\"acquisitionDate\":\"ayer\"}")))
            .isEqualTo(400);
        assertThat(status(send(admin, "POST", "/equipment", "{" + b + ",\"name\":\"X\",\"imageUrl\":\"http://x/y.png\"}")))
            .isEqualTo(400);
        assertThat(status(send(admin, "POST", "/equipment", "{" + b + ",\"name\":\"X\",\"imageUrl\":\"//x/y.png\"}")))
            .isEqualTo(400);
        assertThat(status(send(admin, "POST", "/equipment",
            "{" + b + ",\"name\":\"X\",\"imageUrl\":\"https://cdn.example.com/y.png\"}"))).isEqualTo(201);
    }

    @Test
    void elEstadoCambiaYElEquipoDadoDeBajaEsFinal() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long id = idOf(create(admin, fx.branch(org.id()), "Bicicleta", ""));
        String uri = "/equipment/" + id + "/status";

        MvcResult maint = send(admin, "PATCH", uri, "{\"status\":\"maintenance\"}");
        MvcResult back = send(admin, "PATCH", uri, "{\"status\":\"available\"}");
        MvcResult retire = send(admin, "PATCH", uri, "{\"status\":\"retired\"}");
        MvcResult after = send(admin, "PATCH", uri, "{\"status\":\"available\"}");
        MvcResult same = send(admin, "PATCH", uri, "{\"status\":\"retired\"}");

        assertThat(status(maint)).isEqualTo(200);
        assertThat(body(maint)).contains("\"status\":\"maintenance\"");
        assertThat(status(back)).isEqualTo(200);
        assertThat(status(retire)).isEqualTo(200);
        assertThat(status(after)).isEqualTo(409);
        assertThat(body(after)).contains("EQUIPMENT_RETIRED");
        assertThat(status(same)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status::text FROM equipment WHERE id = ?", String.class, id))
            .isEqualTo("retired");
        assertThat(status(send(admin, "PATCH", uri, "{\"status\":\"x\"}"))).isEqualTo(400);
        assertThat(status(send(admin, "PATCH", uri, "{}"))).isEqualTo(400);
    }

    @Test
    void sePuedeEditarLosDatosSinTocarLaSedeNiElEstado() throws Exception {
        Org org = fx.orgWithAdmin();
        long branch = fx.branch(org.id());
        Cookie admin = fx.login(org.admin());
        long id = idOf(create(admin, branch, "Cinta", ",\"status\":\"maintenance\""));

        MvcResult r = send(admin, "PUT", "/equipment/" + id,
            "{\"name\":\"Cinta de correr\",\"category\":\"Cardio\",\"branchId\":999999,\"status\":\"retired\"}");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"name\":\"Cinta de correr\"").contains("\"category\":\"Cardio\"")
            .contains("\"status\":\"maintenance\"").contains("\"branchId\":" + branch);
        assertThat(status(send(admin, "PUT", "/equipment/" + id, "{\"category\":\"Cardio\"}"))).isEqualTo(400);
    }

    @Test
    void elStaffConsultaPeroNoModifica() throws Exception {
        Org org = fx.orgWithAdmin();
        long branch = fx.branch(org.id());
        long id = idOf(create(fx.login(org.admin()), branch, "Remo", ""));
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        assertThat(body(send(staff, "GET", "/equipment", null))).contains("\"name\":\"Remo\"");
        assertThat(status(send(staff, "GET", "/equipment/" + id, null))).isEqualTo(200);
        assertThat(status(send(staff, "POST", "/equipment", "{\"branchId\":%d,\"name\":\"X\"}".formatted(branch))))
            .isEqualTo(403);
        assertThat(status(send(staff, "PUT", "/equipment/" + id, "{\"name\":\"X\"}"))).isEqualTo(403);
        assertThat(status(send(staff, "PATCH", "/equipment/" + id + "/status", "{\"status\":\"retired\"}")))
            .isEqualTo(403);
        for (String role : new String[] {"trainer", "client"}) {
            assertThat(status(send(fx.login(fx.actor(org.id(), role)), "GET", "/equipment", null))).as(role)
                .isEqualTo(403);
        }
    }

    @Test
    void filtraPorSedeEstadoYCategoriaYNoMuestraOtroGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        long north = fx.branch(org.id());
        long south = fx.branch(org.id());
        Cookie admin = fx.login(org.admin());
        create(admin, north, "Cinta norte", ",\"category\":\"Cardio\"");
        create(admin, south, "Mancuernas sur", ",\"category\":\"Fuerza\",\"status\":\"maintenance\"");
        create(fx.login(other.admin()), fx.branch(other.id()), "Secreto ajeno", "");

        String all = body(send(admin, "GET", "/equipment", null));
        String byBranch = body(send(admin, "GET", "/equipment?branchId=" + south, null));
        String byStatus = body(send(admin, "GET", "/equipment?status=maintenance", null));
        String byCategory = body(send(admin, "GET", "/equipment?category=cardio", null));

        assertThat(all).contains("Cinta norte").contains("Mancuernas sur").doesNotContain("Secreto ajeno");
        assertThat(byBranch).contains("Mancuernas sur").doesNotContain("Cinta norte");
        assertThat(byStatus).contains("Mancuernas sur").doesNotContain("Cinta norte");
        assertThat(byCategory).contains("Cinta norte").doesNotContain("Mancuernas sur");
        assertThat(status(send(admin, "GET", "/equipment?status=roto", null))).isEqualTo(400);
    }

    @Test
    void elEquipoDeOtroGimnasioNoSeVeNiSeModifica() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        long foreign = idOf(create(fx.login(other.admin()), fx.branch(other.id()), "Ajeno", ""));
        Cookie admin = fx.login(org.admin());

        assertThat(status(send(admin, "GET", "/equipment/" + foreign, null))).isEqualTo(404);
        assertThat(status(send(admin, "PUT", "/equipment/" + foreign, "{\"name\":\"Mío\"}"))).isEqualTo(404);
        assertThat(status(send(admin, "PATCH", "/equipment/" + foreign + "/status", "{\"status\":\"retired\"}")))
            .isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT name FROM equipment WHERE id = ?", String.class, foreign))
            .isEqualTo("Ajeno");
    }

    @Test
    void laBaseRechazaDireccionesDeImagenInvalidas() {
        Org org = fx.orgWithAdmin();
        long branch = fx.branch(org.id());

        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("""
                INSERT INTO equipment (organization_id, branch_id, name, status, image_url)
                VALUES (?, ?, 'X', 'available', 'javascript:alert(1)')""", org.id(), branch));
        assertThat(e.getMessage()).contains("ck_equipment_image_url");
    }
}

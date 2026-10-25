package com.nexfit.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

/** NEX-29 (US-06): crear establecimientos. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EstablishmentCreationTests {

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

    private MvcResult create(Cookie session, String json) throws Exception {
        return mvc.perform(post("/establishments").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long idOf(String json) {
        return Long.parseLong(json.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
    }

    // Criterio 1: el Master crea con nombre, correo y zona horaria; queda activo y sin Administrador.
    @Test
    void elMasterCreaUnEstablecimientoActivoYSinAdministrador() throws Exception {
        Cookie master = fx.login(fx.user(true, true));

        MvcResult r = create(master,
            "{\"name\":\"  Gym Norte \",\"contactEmail\":\"norte@nexfit.test\",\"timezone\":\"America/Mexico_City\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        long id = idOf(body(r));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT name, contact_email, timezone, is_active FROM organizations WHERE id = ?", id);
        assertThat(row).containsEntry("name", "Gym Norte").containsEntry("contact_email", "norte@nexfit.test")
            .containsEntry("timezone", "America/Mexico_City").containsEntry("is_active", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE organization_id = ?",
            Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_roles WHERE organization_id = ?",
            Integer.class, id)).isZero();
    }

    // La zona horaria es opcional y por defecto America/Bogota.
    @Test
    void sinZonaHorariaUsaAmericaBogota() throws Exception {
        Cookie master = fx.login(fx.user(true, true));

        MvcResult r = create(master, "{\"name\":\"Gym Sur\",\"contactEmail\":\"sur@nexfit.test\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT timezone FROM organizations WHERE id = ?", String.class,
            idOf(body(r)))).isEqualTo("America/Bogota");
    }

    // Criterio 2: sin nombre o con correo (o zona) inválido se rechaza indicando el campo (400).
    @Test
    void sinNombreOCorreoInvalidoSeRechazaIndicandoElCampo() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        int before = jdbc.queryForObject("SELECT count(*) FROM organizations", Integer.class);

        assertRejected(create(master, "{\"contactEmail\":\"a@nexfit.test\"}"), "name");
        assertRejected(create(master, "{\"name\":\"   \",\"contactEmail\":\"a@nexfit.test\"}"), "name");
        assertRejected(create(master, "{\"name\":\"Gym\"}"), "contactEmail");
        assertRejected(create(master, "{\"name\":\"Gym\",\"contactEmail\":\"no-es-correo\"}"), "contactEmail");
        assertRejected(create(master, "{\"name\":\"Gym\",\"contactEmail\":\"a@nexfit.test\",\"timezone\":\"Marte/Olimpo\"}"),
            "timezone");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM organizations", Integer.class)).isEqualTo(before);
    }

    private void assertRejected(MvcResult r, String field) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"" + field + "\"");
    }

    // Criterio 3 (RN-20): el establecimiento nuevo no es visible para otro gimnasio.
    @Test
    void elEstablecimientoNuevoNoEsVisibleParaOtroGimnasio() throws Exception {
        Org other = fx.orgWithAdmin();
        Cookie master = fx.login(fx.user(true, true));
        MvcResult created = create(master, "{\"name\":\"Gym Oculto\",\"contactEmail\":\"oculto@nexfit.test\"}");
        long id = idOf(body(created));

        Cookie admin = fx.login(other.admin());
        MvcResult r = mvc.perform(get("/auth/establishments").cookie(admin)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"organizationId\":" + other.id())
            .doesNotContain("\"organizationId\":" + id).doesNotContain("Gym Oculto");
    }

    // Criterio 4: quien no es Master recibe 403 y no se crea nada.
    @Test
    void quienNoEsMasterRecibe403() throws Exception {
        Org org = fx.orgWithAdmin();
        int before = jdbc.queryForObject("SELECT count(*) FROM organizations", Integer.class);
        String json = "{\"name\":\"Gym Intruso\",\"contactEmail\":\"intruso@nexfit.test\"}";

        for (String role : new String[] {"staff", "trainer", "client"}) {
            Cookie session = fx.login(fx.actor(org.id(), role));
            MvcResult r = create(session, json);
            assertThat(r.getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(body(r)).contains("ACCESS_DENIED");
        }
        assertThat(create(fx.login(org.admin()), json).getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organizations", Integer.class)).isEqualTo(before);
    }

    // Sin sesión → 401.
    @Test
    void sinSesionRecibe401() throws Exception {
        MvcResult r = mvc.perform(post("/establishments").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Gym\",\"contactEmail\":\"a@nexfit.test\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }
}

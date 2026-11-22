package com.nexfit.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
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

/** Lectura del catálogo de EPS y su mantenimiento por el Master (NEX-89, NEX-30). */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class HealthInsuranceTests {

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

    private MvcResult list(Cookie session) throws Exception {
        return mvc.perform(get("/health-insurance-providers").cookie(session)).andReturn();
    }

    private static String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private long eps(String name, boolean active) {
        return jdbc.queryForObject(
            "INSERT INTO health_insurance_providers (name, is_active) VALUES (?, ?) RETURNING id", Long.class,
            name, active);
    }

    @Test
    void staffAdministradorYClienteVenLasEpsActivasPorNombre() throws Exception {
        Org org = fx.orgWithAdmin();
        long zeta = eps("Zeta Salud", true);
        long alfa = eps("alfa Salud", true);
        eps("Inactiva Salud", false);

        for (Cookie session : new Cookie[] {fx.login(org.admin()), fx.login(fx.actor(org.id(), "staff")),
            fx.login(fx.actor(org.id(), "client"))}) {
            MvcResult r = list(session);

            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(r)).contains("{\"id\":" + alfa + ",\"name\":\"alfa Salud\",\"active\":true}",
                "{\"id\":" + zeta + ",\"name\":\"Zeta Salud\",\"active\":true}").doesNotContain("Inactiva Salud");
            assertThat(body(r).indexOf("alfa Salud")).isLessThan(body(r).indexOf("Zeta Salud"));
        }
    }

    @Test
    void unEntrenadorNoLeeElCatalogoYSinSesionEs401() throws Exception {
        Org org = fx.orgWithAdmin();

        assertThat(list(fx.login(fx.actor(org.id(), "trainer"))).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/health-insurance-providers")).andReturn().getResponse().getStatus())
            .isEqualTo(401);
    }

    private MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Cookie session,
                           String json) throws Exception {
        return mvc.perform(req.with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON).content(json))
            .andReturn();
    }

    @Test
    void elMasterCreaUnaEpsActivaYUnNombreRepetidoSeRechazaSinDistinguirMayusculas() throws Exception {
        Cookie master = fx.login(fx.user(true, true));

        MvcResult creada = send(post("/health-insurance-providers"), master, "{\"name\":\"Salud Nueva Test\"}");
        MvcResult repetida = send(post("/health-insurance-providers"), master, "{\"name\":\"SALUD NUEVA test\"}");
        MvcResult vacia = send(post("/health-insurance-providers"), master, "{\"name\":\"  \"}");

        assertThat(creada.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(creada)).contains("\"name\":\"Salud Nueva Test\"", "\"active\":true");
        assertThat(repetida.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(repetida)).contains("HEALTH_INSURER_NAME_TAKEN");
        assertThat(vacia.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void elMasterRenombraYNoPuedeUsarElNombreDeOtra() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long uno = eps("Renombrar Uno", true);
        eps("Renombrar Dos", true);

        MvcResult ok = send(put("/health-insurance-providers/" + uno), master, "{\"name\":\"Renombrar Uno Bis\"}");
        MvcResult choca = send(put("/health-insurance-providers/" + uno), master, "{\"name\":\"renombrar dos\"}");
        MvcResult mismo = send(put("/health-insurance-providers/" + uno), master, "{\"name\":\"RENOMBRAR UNO BIS\"}");
        MvcResult noExiste = send(put("/health-insurance-providers/999999999"), master, "{\"name\":\"X\"}");

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(choca.getResponse().getStatus()).isEqualTo(409);
        assertThat(mismo.getResponse().getStatus()).isEqualTo(200);
        assertThat(noExiste.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void unaEpsDesactivadaNoSeOfreceAlRegistrarPeroElMasterLaSigueViendo() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie master = fx.login(fx.user(true, true));
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        long id = eps("Desactivable Salud", true);

        MvcResult off = send(patch("/health-insurance-providers/" + id + "/active"), master, "{\"active\":false}");
        MvcResult conFiltro = mvc.perform(get("/health-insurance-providers?includeInactive=true").cookie(master))
            .andReturn();
        MvcResult sinFiltro = list(master);
        MvcResult delStaff = mvc.perform(get("/health-insurance-providers?includeInactive=true").cookie(staff))
            .andReturn();
        MvcResult mal = send(patch("/health-insurance-providers/" + id + "/active"), master, "{\"active\":\"si\"}");
        MvcResult on = send(patch("/health-insurance-providers/" + id + "/active"), master, "{\"active\":true}");

        assertThat(off.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(off)).contains("\"active\":false");
        assertThat(body(conFiltro)).contains("Desactivable Salud");
        assertThat(body(sinFiltro)).doesNotContain("Desactivable Salud");
        assertThat(body(delStaff)).doesNotContain("Desactivable Salud");
        assertThat(mal.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(on)).contains("\"active\":true");
    }

    @Test
    void quienNoEsMasterNoModificaElCatalogo() throws Exception {
        Org org = fx.orgWithAdmin();
        long id = eps("Intocable Salud", true);

        for (Cookie session : new Cookie[] {fx.login(org.admin()), fx.login(fx.actor(org.id(), "staff")),
            fx.login(fx.actor(org.id(), "client"))}) {
            assertThat(send(post("/health-insurance-providers"), session, "{\"name\":\"Otra\"}").getResponse()
                .getStatus()).isEqualTo(403);
            assertThat(send(put("/health-insurance-providers/" + id), session, "{\"name\":\"Otra\"}").getResponse()
                .getStatus()).isEqualTo(403);
            assertThat(send(patch("/health-insurance-providers/" + id + "/active"), session, "{\"active\":false}")
                .getResponse().getStatus()).isEqualTo(403);
        }
    }
}

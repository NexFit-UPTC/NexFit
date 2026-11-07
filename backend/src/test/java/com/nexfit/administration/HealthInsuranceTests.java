package com.nexfit.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
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

/** Lectura del catálogo de EPS (parte de lo que necesita FE-04, NEX-89; la gestión es NEX-30). */
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
            assertThat(body(r)).contains("{\"id\":" + alfa + ",\"name\":\"alfa Salud\"}",
                "{\"id\":" + zeta + ",\"name\":\"Zeta Salud\"}").doesNotContain("Inactiva Salud");
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
}

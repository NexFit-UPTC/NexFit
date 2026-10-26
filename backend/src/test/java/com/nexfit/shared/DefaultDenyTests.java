package com.nexfit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Default-deny: un handler de com.nexfit sin regla responde 403 ACCESS_RULE_MISSING.
 * Contexto aparte: el controller sin anotación no debe existir en las demás pruebas
 * (ver {@link HandlerRulesScanTest}).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(DefaultDenyTests.Probe.class)
class DefaultDenyTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @TestConfiguration
    static class Probe {
        @RestController
        static class UnannotatedController {
            @GetMapping("/test/deny/unannotated")
            String unannotated() {
                return "ok";
            }
        }

        /** La regla puede estar en la clase. */
        @RestController
        @AnyAuthenticated
        static class ClassLevelController {
            @GetMapping("/test/deny/class-level")
            String classLevel() {
                return "ok";
            }
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    AccessFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new AccessFixtures(jdbc, encoder, mvc);
    }

    private MvcResult call(String path, Cookie cookie) throws Exception {
        return mvc.perform(get(path).cookie(cookie)).andReturn();
    }

    @Test
    void unHandlerSinReglaDa403ParaCualquierRolIncluidoElMaster() throws Exception {
        Org org = fx.orgWithAdmin();
        for (Cookie cookie : new Cookie[] {fx.login(org.admin()), fx.login(fx.user(true, true))}) {
            MvcResult r = call("/test/deny/unannotated", cookie);
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("ACCESS_RULE_MISSING");
        }
    }

    @Test
    void laReglaDeClaseCuentaComoRegla() throws Exception {
        Org org = fx.orgWithAdmin();
        assertThat(call("/test/deny/class-level", fx.login(org.admin())).getResponse().getStatus()).isEqualTo(200);
    }
}

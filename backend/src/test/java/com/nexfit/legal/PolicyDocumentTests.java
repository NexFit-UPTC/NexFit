package com.nexfit.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nexfit.shared.AccessFixtures;
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

/** NEX-115: el texto vigente de la política se sirve con versión, fecha, estado y ambos textos. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PolicyDocumentTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private AccessFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new AccessFixtures(jdbc, encoder, mvc);
    }

    private String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void devuelveVersionFechaEstadoYAmbosTextos() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = fixtures.login(fixtures.actor(org.id(), "staff"));

        MvcResult r = mvc.perform(get("/legal/policy").cookie(staff)).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"version\":\"0.1-borrador\"");
        assertThat(body(r)).contains("\"effectiveDate\":\"2026-10-18\"");
        assertThat(body(r)).contains("\"status\":\"borrador\"");
        assertThat(body(r)).contains("Política de tratamiento de datos personales");
        assertThat(body(r)).contains("Autorización expresa para datos de salud");
    }

    @Test
    void sinSesionDa401() throws Exception {
        MvcResult r = mvc.perform(get("/legal/policy")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void masterRecibe403() throws Exception {
        AccessFixtures.Account master = fixtures.user(true, true);
        Cookie masterCookie = fixtures.login(master);

        MvcResult r = mvc.perform(get("/legal/policy").cookie(masterCookie)).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r)).contains("ACCESS_DENIED");
    }
}

package com.nexfit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Comprueba que el esqueleto arranca contra PostgreSQL 18 real.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SkeletonIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreaLaTablaDeSesiones() {
        Integer tablas = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'spring_session'",
            Integer.class);
        assertThat(tablas).isEqualTo(1);
    }

    @Test
    void healthEsPublicoYEstaArriba() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void sinSesionResponde401() throws Exception {
        mvc.perform(get("/cualquier-recurso"))
            .andExpect(status().isUnauthorized());
    }
}

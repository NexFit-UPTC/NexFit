package com.nexfit.bootstrap.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Conjunto {@code demo}: dos gimnasios completos que muestran toda la interfaz con datos ficticios. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DemoSeedTests {

    private static final String PASSWORD = "Clave-De-Prueba-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    DemoSeedService demo;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Cookie login(String email) throws Exception {
        MvcResult r = mvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).as(email).isEqualTo(200);
        return r.getResponse().getCookie("NEXFIT_SESSION");
    }

    private String body(String path, Cookie session) throws Exception {
        MvcResult r = mvc.perform(get(path).cookie(session)).andReturn();
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        return r.getResponse().getContentAsString();
    }

    @Test
    void cargaDosGimnasiosCompletosYEsIdempotente() {
        DemoSeedService.Result first = demo.load(PASSWORD);
        DemoSeedService.Result second = demo.load(PASSWORD);

        assertThat(first.skipped()).isFalse();
        assertThat(first.gyms()).isEqualTo(2);
        assertThat(first.clients()).isEqualTo(24);
        assertThat(first.routines()).isEqualTo(26);
        assertThat(first.workouts()).isEqualTo(50);
        assertThat(second.skipped()).isTrue();
        assertThat(count("SELECT count(*) FROM organizations WHERE name IN ('Iron Peak Fitness', 'Vital Studio')"))
            .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM branches b JOIN organizations o ON o.id = b.organization_id "
            + "WHERE o.name = 'Iron Peak Fitness'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM equipment e JOIN organizations o ON o.id = e.organization_id "
            + "WHERE o.name = 'Iron Peak Fitness' AND e.image_url LIKE '/img/equipos/%'")).isEqualTo(26);
        assertThat(count("SELECT count(*) FROM classes c JOIN organizations o ON o.id = c.organization_id "
            + "WHERE o.name = 'Vital Studio' AND c.status = 'scheduled' AND c.starts_at > now()")).isEqualTo(8);
        assertThat(count("SELECT count(*) FROM class_bookings b JOIN organizations o ON o.id = b.organization_id "
            + "WHERE o.name = 'Vital Studio'")).isPositive();
        assertThat(count("SELECT count(*) FROM trainer_clients tc JOIN organizations o ON o.id = tc.organization_id "
            + "WHERE o.name = 'Iron Peak Fitness' AND tc.is_active")).isEqualTo(6);
    }

    @Test
    void cadaRolDeLosGimnasiosDemoPuedeIniciarSesion() throws Exception {
        demo.load(PASSWORD);

        for (String email : new String[] {"admin.ironpeak@nexfit.test", "staff.ironpeak@nexfit.test",
            "trainer.ironpeak@nexfit.test", "cliente1.ironpeak@nexfit.test", "admin.vital@nexfit.test",
            "cliente12.vital@nexfit.test"}) {
            login(email);
        }
    }

    @Test
    void elClienteUnoTieneUnaRachaDeDoceDiasYElEntrenadorVeASusDosAlumnos() throws Exception {
        demo.load(PASSWORD);

        String progress = body("/my-progress", login("cliente1.ironpeak@nexfit.test"));
        String students = body("/my-students", login("trainer.ironpeak@nexfit.test"));
        String routines = body("/routines", login("cliente1.ironpeak@nexfit.test"));

        assertThat(progress).contains("\"streak\":12");
        assertThat(students.split("\"memberId\"").length - 1).isEqualTo(2);
        assertThat(routines).contains("Fuerza total A");
    }
}

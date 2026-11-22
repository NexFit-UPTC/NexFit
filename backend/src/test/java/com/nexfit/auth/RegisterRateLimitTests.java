package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Tope de registros por hora y conexión (429 con Retry-After). */
@Testcontainers
@SpringBootTest(properties = "nexfit.auth.register-max-per-hour=8")
@AutoConfigureMockMvc
class RegisterRateLimitTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String content(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private String registerBody(String password) {
        int n = SEQ.incrementAndGet();
        return """
                {"documentType":"CC","documentNumber":"R%d","firstName":"Ana","lastName":"Pérez",
                 "birthDate":"1990-01-01","email":"rate%d@nexfit.test","password":"%s"}
                """.formatted(n, n, password);
    }

    @Test
    void unaRafagaDeRegistrosDesdeLaMismaConexionTerminaEn429() throws Exception {
        int tooMany = 0;
        for (int i = 0; i < 20; i++) {
            MvcResult r = mvc.perform(post("/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("Clave-Segura-123"))).andReturn();
            if (r.getResponse().getStatus() == 429) {
                tooMany++;
                assertThat(content(r)).contains("TOO_MANY_REQUESTS");
                assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("3600");
            }
        }

        assertThat(tooMany).as("con tope de 8 por hora, la ráfaga de 20 debe cortarse").isGreaterThan(0);
    }
}

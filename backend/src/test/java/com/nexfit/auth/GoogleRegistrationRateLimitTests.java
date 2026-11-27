package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
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

/**
 * El POST de registro con Google (NEX-148) comparte el contador de {@code POST /auth/register}: los
 * intentos de uno gastan el tope del otro, y al pasarse responde 429 con Retry-After igual, antes de
 * mirar el pendiente.
 */
@Testcontainers
@SpringBootTest(properties = "nexfit.auth.register-max-per-hour=3")
@AutoConfigureMockMvc
class GoogleRegistrationRateLimitTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    private MvcResult postGoogle() throws Exception {
        return mvc.perform(post("/auth/google-registration").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn();
    }

    @Test
    void losIntentosDeRegisterGastanElTopeDelRegistroConGoogle() throws Exception {
        for (int i = 0; i < 3; i++) {
            // Cuerpo vacío: 400, pero cuenta como intento (también los rechazados).
            MvcResult r = mvc.perform(post("/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
        }

        MvcResult limited = postGoogle();

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(new String(limited.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("TOO_MANY_REQUESTS");
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("3600");
    }
}

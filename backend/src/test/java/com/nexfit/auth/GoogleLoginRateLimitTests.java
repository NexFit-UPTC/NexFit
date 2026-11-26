package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Tope por conexión de la cadena de Google (NEX-147): pasado el límite, mismo error genérico del login. */
@Testcontainers
@SpringBootTest(properties = {
        "GOOGLE_CLIENT_ID=google-test-id",
        "GOOGLE_CLIENT_SECRET=google-test-secret",
        "nexfit.auth.google-max-per-hour=2"
})
@AutoConfigureMockMvc
class GoogleLoginRateLimitTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void pasadoElTopeVuelveAlLoginConElErrorGenerico() throws Exception {
        for (int i = 0; i < 2; i++) {
            String location = mvc.perform(get("/oauth2/authorization/google")).andReturn()
                    .getResponse().getRedirectedUrl();
            assertThat(location).startsWith("https://accounts.google.com/");
        }

        var limited = mvc.perform(get("/oauth2/authorization/google")).andReturn().getResponse();

        assertThat(limited.getStatus()).isEqualTo(302);
        assertThat(limited.getRedirectedUrl()).isEqualTo("/login?error=google");
    }
}

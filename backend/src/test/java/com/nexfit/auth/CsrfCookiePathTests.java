package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** HTTP real con context-path /api: la cookie debe ser legible por Angular, que vive en «/» (NEX-80). */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsrfCookiePathTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @LocalServerPort
    int port;

    @Test
    void laCookieXsrfTokenTienePathRaizYNoEsHttpOnly() throws Exception {
        HttpResponse<Void> r = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/actuator/health")).build(),
            HttpResponse.BodyHandlers.discarding());
        String setCookie = r.headers().allValues("Set-Cookie").stream()
            .filter(h -> h.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(setCookie).endsWith("; Path=/").doesNotContain("Path=/api").doesNotContain("HttpOnly");
    }
}

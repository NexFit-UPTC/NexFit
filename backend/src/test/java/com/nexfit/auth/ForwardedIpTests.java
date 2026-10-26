package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** MockMvc no pasa por el RemoteIpValve de Tomcat, por eso aquí la petición es HTTP real. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedIpTests {

    private static final String PASSWORD = "Clave-Segura-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Test
    void auditaLaIpDelClienteYNoLaDelProxyConXForwardedForDeDosSaltos() throws Exception {
        long id = jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', 'IP1', 'Ana', 'Pérez', DATE '1990-01-01', 'ip@nexfit.test', ?, false, true) RETURNING id
            """, Long.class, encoder.encode(PASSWORD));

        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + port + "/api";

        // Como Angular: primero un GET para recibir la cookie XSRF-TOKEN y luego se devuelve en Cookie y cabecera.
        HttpResponse<Void> first = http.send(
            HttpRequest.newBuilder(URI.create(base + "/actuator/health")).build(), HttpResponse.BodyHandlers.discarding());
        String xsrf = first.headers().allValues("Set-Cookie").stream()
            .filter(h -> h.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow()
            .split(";")[0].substring("XSRF-TOKEN=".length());

        HttpResponse<Void> login = http.send(HttpRequest.newBuilder(URI.create(base + "/auth/login"))
            .header("Content-Type", "application/json")
            .header("Cookie", "XSRF-TOKEN=" + xsrf)
            .header("X-XSRF-TOKEN", xsrf)
            .header("X-Forwarded-For", "203.0.113.7, 198.51.100.20")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"email\":\"ip@nexfit.test\",\"password\":\"%s\"}".formatted(PASSWORD)))
            .build(), HttpResponse.BodyHandlers.discarding());

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
            "SELECT host(ip_address) FROM audit_events WHERE event_type = 'LOGIN' AND actor_user_id = ?",
            String.class, id)).isEqualTo("203.0.113.7");
    }
}

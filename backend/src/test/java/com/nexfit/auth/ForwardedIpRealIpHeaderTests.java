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

/** Con CLIENT_IP_HEADER=X-Real-IP (Azure tras Vercel) la IP auditada sale de esa cabecera, no de X-Forwarded-For. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.tomcat.remoteip.remote-ip-header=X-Real-IP")
class ForwardedIpRealIpHeaderTests {

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
    void auditaLaIpDeXRealIpCuandoSeConfiguraEsaCabecera() throws Exception {
        long id = jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', 'IP2', 'Ana', 'Pérez', DATE '1990-01-01', 'realip@nexfit.test', ?, false, true) RETURNING id
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
            .header("X-Real-IP", "203.0.113.9")
            .header("X-Forwarded-For", "198.51.100.20")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"email\":\"realip@nexfit.test\",\"password\":\"%s\"}".formatted(PASSWORD)))
            .build(), HttpResponse.BodyHandlers.discarding());

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
            "SELECT host(ip_address) FROM audit_events WHERE event_type = 'LOGIN' AND actor_user_id = ?",
            String.class, id)).isEqualTo("203.0.113.9");
    }
}

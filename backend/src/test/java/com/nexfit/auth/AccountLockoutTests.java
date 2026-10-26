package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.ReglaDeNegocio;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** US-03 (NEX-23): bloqueo temporal tras 5 intentos fallidos consecutivos (RF-104, RN-38). */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AccountLockoutTests {

    private static final String PASSWORD = "Clave-Segura-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long newUser(String email) {
        int n = 1000 + SEQ.incrementAndGet();
        return jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', ?, 'Ana', 'Pérez', DATE '1990-01-01', ?, ?, false, true) RETURNING id
            """, Long.class, "L" + n, email, encoder.encode(PASSWORD));
    }

    private int login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
            .andReturn().getResponse().getStatus();
    }

    private void failTimes(String email, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(login(email, "incorrecta")).isEqualTo(401);
        }
    }

    private int events(String type, long actor) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = ? AND actor_user_id = ?",
            Integer.class, type, actor);
    }

    private int failedCount(long userId) {
        return jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, userId);
    }

    private Timestamp lockedUntil(long userId) {
        return jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?", Timestamp.class, userId);
    }

    // Criterio 1
    @Test
    @ReglaDeNegocio("RN-38")
    void tras5FallosElSextoIntentoSeRechazaAunqueLaContrasenaSeaCorrecta() throws Exception {
        long id = newUser("cinco@nexfit.test");
        failTimes("cinco@nexfit.test", 5);

        assertThat(lockedUntil(id)).isNotNull().isAfter(new Timestamp(System.currentTimeMillis()));
        assertThat(login("cinco@nexfit.test", PASSWORD)).isEqualTo(401);
    }

    // Criterio 2 (se mueve locked_until al pasado en vez de esperar 15 minutos)
    @Test
    @ReglaDeNegocio("RN-38")
    void pasadosLos15MinutosElAccesoConCredencialesCorrectasVuelve() throws Exception {
        long id = newUser("expira@nexfit.test");
        failTimes("expira@nexfit.test", 5);
        assertThat(login("expira@nexfit.test", PASSWORD)).isEqualTo(401);

        jdbc.update("UPDATE users SET locked_until = now() - interval '1 second' WHERE id = ?", id);

        assertThat(login("expira@nexfit.test", PASSWORD)).isEqualTo(200);
        assertThat(failedCount(id)).isZero();
        assertThat(lockedUntil(id)).isNull();
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void elBloqueoDuraQuinceMinutos() throws Exception {
        long id = newUser("duracion@nexfit.test");
        failTimes("duracion@nexfit.test", 5);

        long minutos = jdbc.queryForObject(
            "SELECT round(extract(epoch FROM (locked_until - now())) / 60) FROM users WHERE id = ?", Long.class, id);
        assertThat(minutos).isEqualTo(15);
    }

    // Criterio 3
    @Test
    @ReglaDeNegocio("RN-38")
    void losFallosYElBloqueoQuedanAuditadosConUsuarioYIp() throws Exception {
        long id = newUser("audit@nexfit.test");
        failTimes("audit@nexfit.test", 5);

        assertThat(events("LOGIN_FAILED", id)).isEqualTo(5);
        assertThat(events("ACCOUNT_LOCKED", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_events
            WHERE actor_user_id = ? AND event_type IN ('LOGIN_FAILED', 'ACCOUNT_LOCKED')
              AND ip_address IS NOT NULL AND occurred_at IS NOT NULL
            """, Integer.class, id)).isEqualTo(6);
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void unIntentoDuranteElBloqueoSeAuditaPeroNoSumaNiAlargaElBloqueo() throws Exception {
        long id = newUser("durante@nexfit.test");
        failTimes("durante@nexfit.test", 5);
        Timestamp bloqueoOriginal = lockedUntil(id);

        assertThat(login("durante@nexfit.test", "incorrecta")).isEqualTo(401);
        assertThat(login("durante@nexfit.test", PASSWORD)).isEqualTo(401);

        assertThat(lockedUntil(id)).isEqualTo(bloqueoOriginal);
        assertThat(failedCount(id)).isZero();
        assertThat(events("LOGIN_FAILED", id)).isEqualTo(7);
        assertThat(events("ACCOUNT_LOCKED", id)).isEqualTo(1);
    }

    // Criterio 4 (+ D-15: el éxito reinicia el contador)
    @Test
    @ReglaDeNegocio("RN-38")
    void con4FallosElAccesoCorrectoEsExitosoYReiniciaElContador() throws Exception {
        long id = newUser("cuatro@nexfit.test");
        failTimes("cuatro@nexfit.test", 4);
        assertThat(failedCount(id)).isEqualTo(4);
        assertThat(lockedUntil(id)).isNull();

        assertThat(login("cuatro@nexfit.test", PASSWORD)).isEqualTo(200);

        assertThat(failedCount(id)).isZero();
        assertThat(events("ACCOUNT_LOCKED", id)).isZero();
        // Tras el reinicio hacen falta otros 5 fallos para bloquear.
        failTimes("cuatro@nexfit.test", 4);
        assertThat(login("cuatro@nexfit.test", PASSWORD)).isEqualTo(200);
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void unCorreoInexistenteNoBloqueaNada() throws Exception {
        long id = newUser("real@nexfit.test");
        for (int i = 0; i < 6; i++) {
            assertThat(login("fantasma@nexfit.test", "incorrecta")).isEqualTo(401);
        }
        assertThat(login("real@nexfit.test", PASSWORD)).isEqualTo(200);
        assertThat(failedCount(id)).isZero();
    }

    /** El contador se actualiza con un solo UPDATE: fallos simultáneos no se pierden ni bloquean dos veces. */
    @Test
    @ReglaDeNegocio("RN-38")
    void fallosSimultaneosBloqueanUnaSolaVez() throws Exception {
        long id = newUser("paralelo@nexfit.test");
        int hilos = 12;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            resultados.add(pool.submit(() -> {
                salida.await();
                return login("paralelo@nexfit.test", "incorrecta");
            }));
        }
        salida.countDown();
        for (Future<Integer> r : resultados) {
            assertThat(r.get()).isEqualTo(401);
        }
        pool.shutdown();

        assertThat(events("ACCOUNT_LOCKED", id)).isEqualTo(1);
        assertThat(events("LOGIN_FAILED", id)).isEqualTo(hilos);
        assertThat(lockedUntil(id)).isNotNull();
        assertThat(login("paralelo@nexfit.test", PASSWORD)).isEqualTo(401);
    }
}

package com.nexfit.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuditServiceTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MockMvc mvc;

    private long newOrganization(String name) {
        return jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES (?, 'x@nexfit.test', 'America/Bogota', true) RETURNING id
            """, Long.class, name);
    }

    private long newUser(String doc) {
        return jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date,
                               email, password_hash, is_platform_admin, is_active)
            VALUES ('CC', ?, 'Ana', 'Pérez', DATE '1990-01-01', ?, 'hash', false, true) RETURNING id
            """, Long.class, doc, doc + "@nexfit.test");
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events", Long.class);
    }

    private long insertOne(String marker) {
        long org = newOrganization("org-" + marker);
        long user = newUser("doc-" + marker);
        tx.executeWithoutResult(s -> audit.record(AuditEventType.LOGIN, user, org,
            "user", user, "192.168.1.10", Map.of("marker", marker)));
        return jdbc.queryForObject(
            "SELECT id FROM audit_events WHERE metadata->>'marker' = ?", Long.class, marker);
    }

    @Test
    void registraUsuarioHoraEstablecimientoEIp() {
        long org = newOrganization("org-insert");
        long user = newUser("doc-insert");

        tx.executeWithoutResult(s -> audit.record(AuditEventType.ROLE_CHANGED, user, org,
            "organization_member", 7L, "10.0.0.5", Map.of("de", "client", "a", "staff")));

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT organization_id, actor_user_id, event_type, entity_type, entity_id,
                   host(ip_address) AS ip, metadata->>'a' AS a,
                   occurred_at > now() - interval '1 minute' AS reciente
            FROM audit_events WHERE actor_user_id = ?
            """, user);
        assertThat(row).containsEntry("organization_id", org)
            .containsEntry("actor_user_id", user)
            .containsEntry("event_type", "ROLE_CHANGED")
            .containsEntry("entity_type", "organization_member")
            .containsEntry("entity_id", 7L)
            .containsEntry("ip", "10.0.0.5")
            .containsEntry("a", "staff")
            .containsEntry("reciente", true);
    }

    @Test
    void aceptaActorYEstablecimientoNulos() {
        tx.executeWithoutResult(s -> audit.record(AuditEventType.LOGIN_FAILED, null, null,
            null, null, null, null));
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_events
            WHERE event_type = 'LOGIN_FAILED' AND actor_user_id IS NULL AND organization_id IS NULL
            """, Long.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rechazaUpdateDeleteYTruncate() {
        long id = insertOne("inmutable");

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET event_type = 'X' WHERE id = ?", id))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE id = ?", id))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_events"))
            .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject(
            "SELECT event_type FROM audit_events WHERE id = ?", String.class, id)).isEqualTo("LOGIN");
    }

    @Test
    void consultaPorFechaUsuarioYTipoEnMenosDeCincoSegundos() {
        long org = newOrganization("org-carga");
        long user = newUser("doc-carga");
        jdbc.update("""
            INSERT INTO audit_events (organization_id, actor_user_id, event_type, occurred_at, metadata)
            SELECT ?, CASE WHEN g % 100 = 0 THEN ? ELSE NULL END,
                   (ARRAY['LOGIN','LOGOUT','LOGIN_FAILED','PAYMENT_VOIDED'])[1 + g % 4],
                   now() - (g || ' minutes')::interval, '{}'::jsonb
            FROM generate_series(1, 200000) g
            """, org, user);
        jdbc.execute("ANALYZE audit_events");

        long inicio = System.nanoTime();
        Long n = jdbc.queryForObject("""
            SELECT count(*) FROM audit_events
            WHERE occurred_at >= now() - interval '30 days'
              AND actor_user_id = ? AND event_type = 'LOGIN'
            """, Long.class, user);
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(n).isGreaterThan(0);
        assertThat(ms).isLessThan(5000);
    }

    @Test
    void siFallaElRegistroLaOperacionDelLlamadorSeRevierte() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            newOrganization("org-rollback");
            audit.record(AuditEventType.LOGIN, null, null, null, null, "no-es-una-ip", null);
        })).isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM organizations WHERE name = 'org-rollback'", Long.class)).isZero();
    }

    @Test
    void exigeUnaTransaccionDelLlamador() {
        assertThatThrownBy(() -> audit.record(AuditEventType.LOGIN, null, null, null, null, null, null))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void nadaSeRegistraSinLlamarAlServicio() throws Exception {
        long antes = count();

        newOrganization("org-sin-auditar");
        mvc.perform(get("/actuator/health"));
        jdbc.queryForList("SELECT * FROM users");

        assertThat(count()).isEqualTo(antes);
    }
}

package com.nexfit.audit.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceso a audit_events. Solo inserción (RN-40): la base de datos rechaza
 * UPDATE, DELETE y TRUNCATE; este repositorio solo inserta y consulta.
 */
@Repository
public class AuditEventRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AuditEventRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(String eventType, Long actorUserId, Long organizationId,
                       String entityType, Long entityId, String ip, Map<String, Object> metadata) {
        jdbc.update("""
            INSERT INTO audit_events
                (organization_id, actor_user_id, event_type, entity_type, entity_id, ip_address, metadata)
            VALUES (?, ?, ?, ?, ?, CAST(? AS inet), CAST(? AS jsonb))
            """,
            organizationId, actorUserId, eventType, entityType, entityId, ip,
            json.writeValueAsString(metadata));
    }

    /** Fila de audit_events tal cual se guarda (ver V2). */
    public record Event(long id, Long organizationId, Long actorUserId, String eventType,
                        String entityType, Long entityId, OffsetDateTime occurredAt,
                        String ipAddress, Map<String, Object> metadata) { }

    /**
     * Búsqueda para US-47 (NEX-31). organizationId nulo = sin filtro (Master, toda la
     * plataforma); con valor = solo ese gimnasio (Admin). Todos los filtros son opcionales.
     * Los filtros caen sobre los índices de V5 (occurred_at, actor_user_id, event_type).
     */
    @SuppressWarnings("unchecked")
    public List<Event> search(Long organizationId, LocalDate from, LocalDate to,
                              Long actorUserId, String eventType) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, organization_id, actor_user_id, event_type, entity_type, entity_id,
                   occurred_at, host(ip_address) AS ip, metadata::text AS metadata_json
            FROM audit_events WHERE 1 = 1
            """);
        List<Object> args = new ArrayList<>();
        if (organizationId != null) {
            sql.append(" AND organization_id = ?");
            args.add(organizationId);
        }
        if (from != null) {
            sql.append(" AND occurred_at::date >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND occurred_at::date <= ?");
            args.add(to);
        }
        if (actorUserId != null) {
            sql.append(" AND actor_user_id = ?");
            args.add(actorUserId);
        }
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            args.add(eventType);
        }
        sql.append(" ORDER BY occurred_at DESC");
        return jdbc.query(sql.toString(), (rs, n) -> row(rs), args.toArray());
    }

    private Event row(ResultSet rs) throws SQLException {
        Map<String, Object> metadata;
        try {
            metadata = json.readValue(rs.getString("metadata_json"), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("metadata inválida en audit_events id=" + rs.getLong("id"), e);
        }
        return new Event(
            rs.getLong("id"),
            rs.getObject("organization_id", Long.class),
            rs.getObject("actor_user_id", Long.class),
            rs.getString("event_type"),
            rs.getString("entity_type"),
            rs.getObject("entity_id", Long.class),
            rs.getObject("occurred_at", OffsetDateTime.class),
            rs.getString("ip"),
            metadata);
    }
}

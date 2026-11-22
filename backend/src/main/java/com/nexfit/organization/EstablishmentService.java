package com.nexfit.organization;

import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creación, consulta, modificación y baja lógica de establecimientos (US-06, NEX-29; US-07, NEX-32; US-08, NEX-33). */
@Service
class EstablishmentService {

    private final JdbcClient jdbc;
    private final AuditService audit;

    EstablishmentService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Establishment(long id, String name, String contactEmail, String timezone, boolean active) { }

    /** Establecimiento con sus conteos; sin ningún dato personal de clientes (RN-08). */
    public record Summary(long id, String name, String contactEmail, String timezone, boolean active,
                          int branchCount, int clientCount) { }

    /** Inserta el establecimiento activo y sin Administrador (se asigna en otra historia). */
    @Transactional
    public Establishment create(String name, String contactEmail, String timezone) {
        try {
            return jdbc.sql("""
                    INSERT INTO organizations (name, contact_email, timezone, is_active)
                    VALUES (:name, :email, :tz, true)
                    RETURNING id, name, contact_email, timezone, is_active
                    """)
                .param("name", name).param("email", contactEmail).param("tz", timezone)
                .query((rs, n) -> new Establishment(rs.getLong("id"), rs.getString("name"),
                    rs.getString("contact_email"), rs.getString("timezone"), rs.getBoolean("is_active")))
                .single();
        } catch (DataIntegrityViolationException e) {
            // check_violation del trigger organizations_timezone_valid: pg_timezone_names no es idéntico a ZoneId.
            throw new InvalidTimezoneException();
        }
    }

    /** Todos los establecimientos, ordenados por nombre; solo el Master los ve todos (NEX-32). */
    @Transactional(readOnly = true)
    public List<Summary> list() {
        return jdbc.sql("""
                SELECT o.id, o.name, o.contact_email, o.timezone, o.is_active,
                       (SELECT count(*) FROM branches b WHERE b.organization_id = o.id) AS branch_count,
                       (SELECT count(DISTINCT mr.member_id) FROM member_roles mr
                         WHERE mr.organization_id = o.id AND mr.role::text = 'client') AS client_count
                  FROM organizations o
              ORDER BY lower(o.name), o.id
                """)
            .query(EstablishmentService::mapSummary).list();
    }

    /** Un establecimiento con sus conteos; vacío si no existe. */
    @Transactional(readOnly = true)
    public Optional<Summary> find(long id) {
        return jdbc.sql("""
                SELECT o.id, o.name, o.contact_email, o.timezone, o.is_active,
                       (SELECT count(*) FROM branches b WHERE b.organization_id = o.id) AS branch_count,
                       (SELECT count(DISTINCT mr.member_id) FROM member_roles mr
                         WHERE mr.organization_id = o.id AND mr.role::text = 'client') AS client_count
                  FROM organizations o
                 WHERE o.id = :id
                """)
            .param("id", id)
            .query(EstablishmentService::mapSummary).optional();
    }

    /** Modifica nombre, correo y zona horaria; no cambia el estado. Vacío si no existe. */
    @Transactional
    public Optional<Establishment> update(long id, String name, String contactEmail, String timezone) {
        try {
            return jdbc.sql("""
                    UPDATE organizations
                       SET name = :name, contact_email = :email, timezone = :tz
                     WHERE id = :id
                    RETURNING id, name, contact_email, timezone, is_active
                    """)
                .param("id", id).param("name", name).param("email", contactEmail).param("tz", timezone)
                .query((rs, n) -> new Establishment(rs.getLong("id"), rs.getString("name"),
                    rs.getString("contact_email"), rs.getString("timezone"), rs.getBoolean("is_active")))
                .optional();
        } catch (DataIntegrityViolationException e) {
            throw new InvalidTimezoneException();
        }
    }

    /**
     * Activa o desactiva el establecimiento (US-08, RN-33). Es baja lógica: no toca ningún otro dato, así que
     * el historial queda intacto. Es idempotente y solo audita cuando el estado realmente cambia. Vacío si no
     * existe. Los usuarios del establecimiento dejan de poder operar en él en su siguiente solicitud, porque
     * {@code SessionUserFilter} consulta el estado en cada una.
     */
    @Transactional
    public Optional<Establishment> setActive(long id, boolean active, long actorUserId, String ip) {
        Optional<Boolean> current = jdbc.sql("SELECT is_active FROM organizations WHERE id = :id FOR UPDATE")
            .param("id", id).query(Boolean.class).optional();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (current.get() != active) {
            jdbc.sql("UPDATE organizations SET is_active = :active WHERE id = :id")
                .param("active", active).param("id", id).update();
            audit.record(active ? AuditEventType.ORGANIZATION_REACTIVATED : AuditEventType.ORGANIZATION_DEACTIVATED,
                actorUserId, id, "organization", id, ip, null);
        }
        return jdbc.sql("SELECT id, name, contact_email, timezone, is_active FROM organizations WHERE id = :id")
            .param("id", id)
            .query((rs, n) -> new Establishment(rs.getLong("id"), rs.getString("name"),
                rs.getString("contact_email"), rs.getString("timezone"), rs.getBoolean("is_active")))
            .optional();
    }

    private static Summary mapSummary(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Summary(rs.getLong("id"), rs.getString("name"), rs.getString("contact_email"),
            rs.getString("timezone"), rs.getBoolean("is_active"),
            rs.getInt("branch_count"), rs.getInt("client_count"));
    }
}
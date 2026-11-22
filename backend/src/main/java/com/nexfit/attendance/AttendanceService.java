package com.nexfit.attendance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingreso y salida de clientes en las sedes (US-36, US-37, US-38). El ingreso exige una membresía vigente con
 * la fecha local del gimnasio (RN-27); un ingreso abierto de un día anterior se cierra solo con salida estimada
 * al final de ese día local ({@code auto_closed}). Toda consulta lleva {@code organization_id}.
 */
@Service
class AttendanceService {

    private static final String SELECT = """
        SELECT ci.id, ci.member_id, u.first_name || ' ' || u.last_name AS member_name, ci.branch_id,
               b.name AS branch_name, ci.check_in_at, ci.check_out_at, ci.auto_closed,
               ci.registered_by_member_id, nullif(btrim(ru.first_name || ' ' || ru.last_name), '') AS registered_by_name
          FROM check_ins ci
          JOIN organization_members om ON om.id = ci.member_id
          JOIN users u ON u.id = om.user_id
          JOIN branches b ON b.id = ci.branch_id
          LEFT JOIN organization_members rm ON rm.id = ci.registered_by_member_id
          LEFT JOIN users ru ON ru.id = rm.user_id
        """;

    private final JdbcClient jdbc;

    AttendanceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record CheckIn(long id, long memberId, String memberName, long branchId, String branchName,
                          OffsetDateTime checkInAt, OffsetDateTime checkOutAt, boolean autoClosed,
                          Long registeredByMemberId, String registeredByName) { }

    public record Filter(Long memberId, OffsetDateTime from, OffsetDateTime to, Long trainerMemberId,
                         boolean onlyOpen) { }

    /** El ingreso o la salida no procede en el estado actual (409), con un código estable. */
    static class Rejected extends RuntimeException {
        private final String code;

        Rejected(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /** Un dato de la solicitud no corresponde al gimnasio (400). */
    static class InvalidReference extends RuntimeException {
        private final String field;

        InvalidReference(String field, String message) {
            super(message, null, false, false);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /** Un Cliente intenta registrar la salida de otro (403). */
    static class NotYours extends RuntimeException {
        NotYours() {
            super(null, null, false, false);
        }
    }

    /**
     * Registra el ingreso. {@code actorMemberId} es nulo cuando el propio cliente lo registra. Si quedó un ingreso
     * abierto de un día anterior se cierra con marca de cierre automático y este se acepta.
     */
    @Transactional
    public CheckIn checkIn(long orgId, long memberId, long branchId, Long actorMemberId) {
        boolean client = jdbc.sql("""
                SELECT fn_member_is_active(:member) AND EXISTS (
                         SELECT 1 FROM member_roles r
                          WHERE r.member_id = :member AND r.organization_id = :org AND r.role = 'client')""")
            .param("member", memberId).param("org", orgId).query(Boolean.class).single();
        if (!client) {
            throw new InvalidReference("memberId", "El cliente no existe en el gimnasio o está inactivo");
        }
        boolean branch = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM branches WHERE id = :id AND organization_id = :org AND is_active)""")
            .param("id", branchId).param("org", orgId).query(Boolean.class).single();
        if (!branch) {
            throw new InvalidReference("branchId", "La sede no existe en el gimnasio o está inactiva");
        }
        LocalDate today = jdbc.sql("SELECT fn_org_local_date(:org, now())").param("org", orgId)
            .query(LocalDate.class).single();
        boolean valid = jdbc.sql("SELECT fn_has_valid_membership(:member, :today)")
            .param("member", memberId).param("today", today).query(Boolean.class).single();
        if (!valid) {
            throw new Rejected("NO_VALID_MEMBERSHIP", noMembershipReason(orgId, memberId, today));
        }
        // Una sola fila abierta por miembro (índice parcial): se bloquea mientras se decide.
        Optional<OpenRow> open = jdbc.sql("""
                SELECT id, fn_org_local_date(organization_id, check_in_at) AS day
                  FROM check_ins WHERE member_id = :member AND check_out_at IS NULL FOR UPDATE""")
            .param("member", memberId)
            .query((rs, n) -> new OpenRow(rs.getLong("id"), rs.getObject("day", LocalDate.class)))
            .optional();
        if (open.isPresent()) {
            if (open.get().day().equals(today)) {
                throw new Rejected("ALREADY_CHECKED_IN", "El cliente ya tiene un ingreso abierto hoy");
            }
            jdbc.sql("""
                    UPDATE check_ins
                       SET check_out_at = ((:day + 1)::timestamp AT TIME ZONE
                                           (SELECT timezone FROM organizations WHERE id = :org)) - interval '1 second',
                           auto_closed = true
                     WHERE id = :id""")
                .param("day", open.get().day()).param("org", orgId).param("id", open.get().id()).update();
        }
        long id;
        try {
            id = jdbc.sql("""
                    INSERT INTO check_ins (organization_id, member_id, branch_id, registered_by_member_id,
                                           check_in_at, auto_closed)
                    VALUES (:org, :member, :branch, :actor, now(), false) RETURNING id""")
                .param("org", orgId).param("member", memberId).param("branch", branchId)
                .param("actor", actorMemberId, java.sql.Types.BIGINT)
                .query(Long.class).single();
        } catch (DataIntegrityViolationException e) {
            throw new Rejected("ALREADY_CHECKED_IN", "El cliente ya tiene un ingreso abierto");
        }
        return find(orgId, id).orElseThrow();
    }

    private record OpenRow(long id, LocalDate day) { }

    /** Motivo legible por el que no hay membresía vigente hoy (RN-27). */
    private String noMembershipReason(long orgId, long memberId, LocalDate today) {
        Optional<String> last = jdbc.sql("""
                SELECT CASE WHEN status = 'cancelled' THEN 'cancelled'
                            WHEN status = 'suspended' THEN 'suspended'
                            WHEN start_date > :today THEN 'scheduled'
                            ELSE 'expired' END
                  FROM memberships WHERE member_id = :member AND organization_id = :org
                  ORDER BY (status = 'active') DESC, end_date DESC LIMIT 1""")
            .param("today", today).param("member", memberId).param("org", orgId).query(String.class).optional();
        return switch (last.orElse("none")) {
            case "cancelled" -> "La membresía del cliente está cancelada";
            case "suspended" -> "La membresía del cliente está suspendida";
            case "scheduled" -> "La membresía del cliente aún no comienza";
            case "expired" -> "La membresía del cliente está vencida";
            default -> "El cliente no tiene membresía";
        };
    }

    /** Cierra el ingreso con la hora actual. {@code onlyMemberId} no es nulo cuando quien lo pide es el Cliente. */
    @Transactional
    public Optional<CheckIn> checkOut(long orgId, long id, Long onlyMemberId) {
        Optional<CheckIn> current = find(orgId, id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        CheckIn c = current.get();
        if (onlyMemberId != null && c.memberId() != onlyMemberId) {
            throw new NotYours();
        }
        if (c.checkOutAt() != null) {
            throw new Rejected("ALREADY_CHECKED_OUT", "El ingreso ya tiene salida registrada");
        }
        jdbc.sql("""
                UPDATE check_ins SET check_out_at = greatest(now(), check_in_at)
                 WHERE id = :id AND organization_id = :org AND check_out_at IS NULL""")
            .param("id", id).param("org", orgId).update();
        return find(orgId, id);
    }

    /** Historial por ingreso más reciente; máximo 200 filas. */
    @Transactional(readOnly = true)
    public List<CheckIn> list(long orgId, Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE ci.organization_id = :org");
        List<Object[]> params = new ArrayList<>();
        if (filter.memberId() != null) {
            where.append(" AND ci.member_id = :member");
            params.add(new Object[] {"member", filter.memberId()});
        }
        if (filter.from() != null) {
            where.append(" AND ci.check_in_at >= :from");
            params.add(new Object[] {"from", filter.from()});
        }
        if (filter.to() != null) {
            where.append(" AND ci.check_in_at < :to");
            params.add(new Object[] {"to", filter.to()});
        }
        if (filter.onlyOpen()) {
            where.append(" AND ci.check_out_at IS NULL");
        }
        if (filter.trainerMemberId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM trainer_clients tc WHERE tc.trainer_member_id = :trainer"
                + " AND tc.client_member_id = ci.member_id AND tc.organization_id = ci.organization_id"
                + " AND tc.is_active)");
            params.add(new Object[] {"trainer", filter.trainerMemberId()});
        }
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT + where + " ORDER BY ci.check_in_at DESC, ci.id DESC LIMIT 200")
            .param("org", orgId);
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(AttendanceService::map).list();
    }

    @Transactional(readOnly = true)
    public Optional<CheckIn> find(long orgId, long id) {
        return jdbc.sql(SELECT + " WHERE ci.id = :id AND ci.organization_id = :org")
            .param("id", id).param("org", orgId).query(AttendanceService::map).optional();
    }

    @Transactional(readOnly = true)
    public Optional<Long> memberIdOf(long orgId, long userId) {
        return jdbc.sql("""
                SELECT id FROM organization_members
                 WHERE organization_id = :org AND user_id = :user AND is_active""")
            .param("org", orgId).param("user", userId).query(Long.class).optional();
    }

    /** Zona horaria del gimnasio (RN-41): los rangos de fechas se leen en ella. */
    @Transactional(readOnly = true)
    public java.time.ZoneId zoneOf(long orgId) {
        return java.time.ZoneId.of(jdbc.sql("SELECT timezone FROM organizations WHERE id = :org")
            .param("org", orgId).query(String.class).single());
    }

    private static CheckIn map(ResultSet rs, int row) throws SQLException {
        return new CheckIn(rs.getLong("id"), rs.getLong("member_id"), rs.getString("member_name"),
            rs.getLong("branch_id"), rs.getString("branch_name"), rs.getObject("check_in_at", OffsetDateTime.class),
            rs.getObject("check_out_at", OffsetDateTime.class), rs.getBoolean("auto_closed"),
            rs.getObject("registered_by_member_id", Long.class), rs.getString("registered_by_name"));
    }
}

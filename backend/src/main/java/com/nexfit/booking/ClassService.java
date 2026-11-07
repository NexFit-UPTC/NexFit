package com.nexfit.booking;

import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Programación de clases del gimnasio (US-29, NEX-40). Toda consulta lleva {@code organization_id}: una
 * clase, sede o entrenador de otro gimnasio es indistinguible de uno inexistente (RN-20).
 *
 * <p>Reglas que aplica este servicio antes de tocar la base (para dar un error claro): sede activa del
 * gimnasio (RN-33), entrenador del gimnasio sin otra clase solapada (RN-36), capacidad no inferior a las
 * reservas confirmadas (RN-36) y fecha u hora intocables con reservas confirmadas (D-21). El trigger
 * {@code classes_validate} de la BD repite la de capacidad como última barrera.
 */
@Service
class ClassService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private static final String SELECT = """
        SELECT c.id, c.name, c.description, c.branch_id, b.name AS branch_name, c.trainer_member_id,
               nullif(btrim(u.first_name || ' ' || u.last_name), '') AS trainer_name,
               c.starts_at, c.duration_minutes, c.capacity, c.status::text AS status,
               (SELECT count(*) FROM class_bookings cb
                 WHERE cb.class_id = c.id AND cb.status = 'confirmed') AS confirmed_bookings
          FROM classes c
          JOIN branches b ON b.id = c.branch_id AND b.organization_id = c.organization_id
          LEFT JOIN organization_members m ON m.id = c.trainer_member_id AND m.organization_id = c.organization_id
          LEFT JOIN users u ON u.id = m.user_id
        """;

    private final JdbcClient jdbc;
    private final AuditService audit;

    ClassService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Clase con sus reservas confirmadas y cupos libres (nunca negativos). */
    public record GymClass(long id, String name, String description, long branchId, String branchName,
                           Long trainerMemberId, String trainerName, OffsetDateTime startsAt,
                           int durationMinutes, int capacity, String status, int confirmedBookings,
                           int freeSlots) { }

    /** Datos editables de una clase, ya validados por el controlador. */
    public record ClassData(String name, String description, long branchId, Long trainerMemberId,
                            OffsetDateTime startsAt, int durationMinutes, int capacity) { }

    public record ClassPage(List<GymClass> content, int page, int size, long totalElements, int totalPages) { }

    /** Filtros del listado; todos opcionales. {@code from} incluye, {@code to} excluye. */
    public record ClassFilter(Long branchId, Long trainerMemberId, OffsetDateTime from, OffsetDateTime to,
                              String status) { }

    /** Dato rechazado con el campo al que corresponde (400). */
    static class InvalidClassException extends RuntimeException {
        private final String field;

        InvalidClassException(String field, String message) {
            super(message, null, false, false);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /** El entrenador ya tiene otra clase programada que se cruza con ese horario (RN-36). */
    static class TrainerOverlapException extends RuntimeException {
        TrainerOverlapException() {
            super(null, null, false, false);
        }
    }

    /** La capacidad nueva es menor que las reservas confirmadas (RN-36). */
    static class CapacityBelowBookingsException extends RuntimeException {
        private final int confirmed;

        CapacityBelowBookingsException(int confirmed) {
            super(null, null, false, false);
            this.confirmed = confirmed;
        }

        int confirmed() {
            return confirmed;
        }
    }

    /** La clase tiene reservas confirmadas y no admite cambiar fecha ni hora (D-21). */
    static class ClassHasBookingsException extends RuntimeException {
        ClassHasBookingsException() {
            super(null, null, false, false);
        }
    }

    /** La clase está cancelada: es terminal y no admite cambios. */
    static class ClassCancelledException extends RuntimeException {
        ClassCancelledException() {
            super(null, null, false, false);
        }
    }

    /** Programa una clase nueva en estado {@code scheduled}. */
    @Transactional
    public GymClass create(long organizationId, ClassData data) {
        requireActiveBranch(organizationId, data.branchId());
        if (data.trainerMemberId() != null) {
            lockTrainer(organizationId, data.trainerMemberId());
            requireNoOverlap(organizationId, data.trainerMemberId(), data.startsAt(), data.durationMinutes(), null);
        }
        long id = jdbc.sql("""
                INSERT INTO classes (organization_id, branch_id, trainer_member_id, name, description,
                                     starts_at, duration_minutes, capacity, status)
                VALUES (:org, :branch, :trainer, :name, :description, :startsAt, :duration, :capacity, 'scheduled')
                RETURNING id""")
            .param("org", organizationId).param("branch", data.branchId())
            .param("trainer", data.trainerMemberId()).param("name", data.name())
            .param("description", data.description()).param("startsAt", data.startsAt())
            .param("duration", data.durationMinutes()).param("capacity", data.capacity())
            .query(Long.class).single();
        return find(organizationId, id).orElseThrow();
    }

    /**
     * Clases del gimnasio por fecha de inicio. {@code onlyScheduled} limita el resultado a las programadas
     * (lo que ve quien no administra clases); si es falso se respeta el filtro {@code status}.
     */
    @Transactional(readOnly = true)
    public ClassPage list(long organizationId, ClassFilter filter, boolean onlyScheduled, int page, int size) {
        int pageSize = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int pageIndex = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" WHERE c.organization_id = :org");
        List<Object[]> params = new ArrayList<>();
        if (filter.branchId() != null) {
            where.append(" AND c.branch_id = :branch");
            params.add(new Object[] {"branch", filter.branchId()});
        }
        if (filter.trainerMemberId() != null) {
            where.append(" AND c.trainer_member_id = :trainer");
            params.add(new Object[] {"trainer", filter.trainerMemberId()});
        }
        if (filter.from() != null) {
            where.append(" AND c.starts_at >= :from");
            params.add(new Object[] {"from", filter.from()});
        }
        if (filter.to() != null) {
            where.append(" AND c.starts_at < :to");
            params.add(new Object[] {"to", filter.to()});
        }
        String status = onlyScheduled ? "scheduled" : filter.status();
        if (status != null) {
            where.append(" AND c.status = CAST(:status AS class_status)");
            params.add(new Object[] {"status", status});
        }

        JdbcClient.StatementSpec count = jdbc.sql("SELECT count(*) FROM classes c" + where).param("org", organizationId);
        JdbcClient.StatementSpec rows = jdbc.sql(SELECT + where + " ORDER BY c.starts_at, c.id LIMIT :limit OFFSET :offset")
            .param("org", organizationId).param("limit", pageSize).param("offset", (long) pageIndex * pageSize);
        for (Object[] p : params) {
            count = count.param((String) p[0], p[1]);
            rows = rows.param((String) p[0], p[1]);
        }
        long total = count.query(Long.class).single();
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new ClassPage(rows.query(ClassService::map).list(), pageIndex, pageSize, total, totalPages);
    }

    /** La clase del gimnasio; vacía si no existe o es de otro gimnasio. */
    @Transactional(readOnly = true)
    public Optional<GymClass> find(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id AND c.organization_id = :org")
            .param("id", id).param("org", organizationId)
            .query(ClassService::map).optional();
    }

    /** Reemplaza los datos de la clase. Vacío si no existe en el gimnasio. */
    @Transactional
    public Optional<GymClass> update(long organizationId, long id, ClassData data) {
        Optional<ClassRow> current = jdbc.sql("""
                SELECT starts_at, duration_minutes, capacity, status::text AS status,
                       (SELECT count(*) FROM class_bookings cb
                         WHERE cb.class_id = classes.id AND cb.status = 'confirmed') AS confirmed
                  FROM classes WHERE id = :id AND organization_id = :org FOR UPDATE""")
            .param("id", id).param("org", organizationId)
            .query((rs, n) -> new ClassRow(rs.getObject("starts_at", OffsetDateTime.class),
                rs.getInt("duration_minutes"), rs.getInt("capacity"), rs.getString("status"),
                rs.getInt("confirmed")))
            .optional();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        ClassRow row = current.get();
        if (!"scheduled".equals(row.status())) {
            throw new ClassCancelledException();
        }
        if (row.confirmed() > 0 && !row.startsAt().toInstant().equals(data.startsAt().toInstant())) {
            throw new ClassHasBookingsException();
        }
        if (data.capacity() < row.confirmed()) {
            throw new CapacityBelowBookingsException(row.confirmed());
        }
        requireActiveBranch(organizationId, data.branchId());
        if (data.trainerMemberId() != null) {
            lockTrainer(organizationId, data.trainerMemberId());
            requireNoOverlap(organizationId, data.trainerMemberId(), data.startsAt(), data.durationMinutes(), id);
        }
        jdbc.sql("""
                UPDATE classes
                   SET name = :name, description = :description, branch_id = :branch,
                       trainer_member_id = :trainer, starts_at = :startsAt,
                       duration_minutes = :duration, capacity = :capacity
                 WHERE id = :id AND organization_id = :org""")
            .param("id", id).param("org", organizationId).param("name", data.name())
            .param("description", data.description()).param("branch", data.branchId())
            .param("trainer", data.trainerMemberId()).param("startsAt", data.startsAt())
            .param("duration", data.durationMinutes()).param("capacity", data.capacity())
            .update();
        return find(organizationId, id);
    }

    /**
     * Cancela la clase (estado final) y, en la misma transacción, todas sus reservas confirmadas (US-30). Si la
     * clase ya inició el motivo es obligatorio (lo exige también el trigger). Vacío si no existe en el gimnasio.
     */
    @Transactional
    public Optional<GymClass> cancel(long organizationId, long actorUserId, long id, String reason, String ip) {
        Optional<ClassRow> current = jdbc.sql("""
                SELECT starts_at, duration_minutes, capacity, status::text AS status, 0 AS confirmed
                  FROM classes WHERE id = :id AND organization_id = :org FOR UPDATE""")
            .param("id", id).param("org", organizationId)
            .query((rs, n) -> new ClassRow(rs.getObject("starts_at", OffsetDateTime.class),
                rs.getInt("duration_minutes"), rs.getInt("capacity"), rs.getString("status"), 0))
            .optional();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        ClassRow row = current.get();
        if (!"scheduled".equals(row.status())) {
            throw new ClassCancelledException();
        }
        if (!row.startsAt().isAfter(OffsetDateTime.now()) && reason == null) {
            throw new InvalidClassException("reason", "La clase ya inició: indica el motivo de la cancelación");
        }
        int bookings = jdbc.sql("""
                UPDATE class_bookings SET status = 'cancelled', cancelled_at = now()
                 WHERE class_id = :id AND organization_id = :org AND status = 'confirmed'""")
            .param("id", id).param("org", organizationId).update();
        jdbc.sql("""
                UPDATE classes SET status = 'cancelled', cancelled_at = now(), cancel_reason = :reason
                 WHERE id = :id AND organization_id = :org""")
            .param("reason", reason).param("id", id).param("org", organizationId).update();
        Map<String, Object> metadata = reason == null ? Map.of("cancelledBookings", bookings)
            : Map.of("cancelledBookings", bookings, "reason", reason);
        audit.record(AuditEventType.CLASS_CANCELLED, actorUserId, organizationId, "class", id, ip, metadata);
        return find(organizationId, id);
    }

    private record ClassRow(OffsetDateTime startsAt, int durationMinutes, int capacity, String status,
                            int confirmed) { }

    /** La sede debe ser del gimnasio y estar activa (RN-33); no se distingue "no existe" de "es de otro". */
    private void requireActiveBranch(long organizationId, long branchId) {
        boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM branches WHERE id = :id AND organization_id = :org"
                + " AND is_active)")
            .param("id", branchId).param("org", organizationId).query(Boolean.class).single();
        if (!ok) {
            throw new InvalidClassException("branchId", "La sede no existe en el gimnasio o está inactiva");
        }
    }

    /**
     * Bloquea la afiliación del entrenador hasta el fin de la transacción, de modo que dos programaciones
     * simultáneas del mismo entrenador se validen una tras otra, y comprueba que sea un entrenador activo
     * del gimnasio.
     */
    private void lockTrainer(long organizationId, long memberId) {
        boolean ok = jdbc.sql("""
                SELECT EXISTS (
                  SELECT 1 FROM (SELECT m.id FROM organization_members m
                                  WHERE m.id = :id AND m.organization_id = :org AND m.is_active
                                    FOR UPDATE) locked
                   WHERE EXISTS (SELECT 1 FROM member_roles r
                                  WHERE r.member_id = locked.id AND r.role = 'trainer')
                     AND EXISTS (SELECT 1 FROM trainer_profiles p WHERE p.member_id = locked.id))""")
            .param("id", memberId).param("org", organizationId).query(Boolean.class).single();
        if (!ok) {
            throw new InvalidClassException("trainerMemberId",
                "El entrenador no existe en el gimnasio, está inactivo o no tiene el rol de entrenador");
        }
    }

    /** RN-36: ninguna otra clase programada del entrenador se cruza con [inicio, inicio + duración). */
    private void requireNoOverlap(long organizationId, long trainerMemberId, OffsetDateTime startsAt,
                                  int durationMinutes, Long exceptClassId) {
        boolean overlap = jdbc.sql("""
                SELECT EXISTS (
                  SELECT 1 FROM classes
                   WHERE organization_id = :org AND trainer_member_id = :trainer AND status = 'scheduled'
                     AND (:except IS NULL OR id <> :except)
                     AND starts_at < :startsAt + make_interval(mins => :duration)
                     AND starts_at + make_interval(mins => duration_minutes) > :startsAt)""")
            .param("org", organizationId).param("trainer", trainerMemberId)
            .param("except", exceptClassId, java.sql.Types.BIGINT)
            .param("startsAt", startsAt).param("duration", durationMinutes)
            .query(Boolean.class).single();
        if (overlap) {
            throw new TrainerOverlapException();
        }
    }

    private static GymClass map(ResultSet rs, int row) throws SQLException {
        int capacity = rs.getInt("capacity");
        int confirmed = rs.getInt("confirmed_bookings");
        Long trainer = rs.getObject("trainer_member_id", Long.class);
        return new GymClass(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
            rs.getLong("branch_id"), rs.getString("branch_name"), trainer,
            rs.getString("trainer_name"), rs.getObject("starts_at", OffsetDateTime.class),
            rs.getInt("duration_minutes"), capacity, rs.getString("status"), confirmed,
            Math.max(capacity - confirmed, 0));
    }
}

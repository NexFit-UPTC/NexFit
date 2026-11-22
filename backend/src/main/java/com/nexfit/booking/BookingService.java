package com.nexfit.booking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservas de cupos en clases (US-32, US-33, US-34). Las reglas también las impone la base de datos (trigger
 * de reservas: clase programada y sin iniciar, membresía válida el día de la clase, cupo disponible con la
 * clase bloqueada); aquí se comprueban antes para responder con un motivo claro y se traduce la carrera.
 * Toda consulta lleva {@code organization_id}: una reserva o clase de otro gimnasio no existe (RN-11).
 */
@Service
class BookingService {

    private static final String SELECT = """
        SELECT b.id, b.class_id, c.name AS class_name, c.starts_at AS class_starts_at, br.name AS branch_name,
               b.member_id, u.first_name || ' ' || u.last_name AS member_name, b.status::text AS status,
               b.booked_at, b.cancelled_at
          FROM class_bookings b
          JOIN classes c ON c.id = b.class_id
          JOIN branches br ON br.id = c.branch_id
          JOIN organization_members om ON om.id = b.member_id
          JOIN users u ON u.id = om.user_id
        """;

    private final JdbcClient jdbc;

    BookingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Booking(long id, long classId, String className, OffsetDateTime classStartsAt, String branchName,
                          long memberId, String memberName, String status, OffsetDateTime bookedAt,
                          OffsetDateTime cancelledAt) { }

    /** Filtros opcionales; {@code trainerMemberId} limita a las clases de ese entrenador. */
    public record Filter(Long memberId, Long classId, String status, Long trainerMemberId) { }

    /** La reserva no puede hacerse o cancelarse en el estado actual (409), con un código estable. */
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

    /** Un Cliente intenta cancelar la reserva de otro (403). */
    static class NotYours extends RuntimeException {
        NotYours() {
            super(null, null, false, false);
        }
    }

    private record ClassRow(OffsetDateTime startsAt, String status, int capacity) { }

    /** Reserva un cupo para el cliente; ninguna validación fallida deja una reserva a medias. */
    @Transactional
    public Booking create(long orgId, long memberId, long classId) {
        ClassRow cls = jdbc.sql("""
                SELECT starts_at, status::text AS status, capacity FROM classes
                 WHERE id = :id AND organization_id = :org""")
            .param("id", classId).param("org", orgId)
            .query((rs, n) -> new ClassRow(rs.getObject("starts_at", OffsetDateTime.class), rs.getString("status"),
                rs.getInt("capacity")))
            .optional().orElseThrow(() -> new InvalidReference("classId", "La clase no existe en el gimnasio"));
        boolean client = jdbc.sql("""
                SELECT fn_member_is_active(:member) AND EXISTS (
                         SELECT 1 FROM member_roles r
                          WHERE r.member_id = :member AND r.organization_id = :org AND r.role = 'client')""")
            .param("member", memberId).param("org", orgId).query(Boolean.class).single();
        if (!client) {
            throw new InvalidReference("memberId", "El cliente no existe en el gimnasio o está inactivo");
        }
        if (cls.status().equals("cancelled")) {
            throw new Rejected("CLASS_CANCELLED", "La clase está cancelada");
        }
        if (!cls.startsAt().isAfter(OffsetDateTime.now())) {
            throw new Rejected("CLASS_STARTED", "La clase ya inició: no admite nuevas reservas");
        }
        boolean validMembership = jdbc.sql("""
                SELECT fn_has_valid_membership(:member, fn_org_local_date(:org, :startsAt))""")
            .param("member", memberId).param("org", orgId).param("startsAt", cls.startsAt())
            .query(Boolean.class).single();
        if (!validMembership) {
            throw new Rejected("NO_VALID_MEMBERSHIP",
                "El cliente no tiene una membresía vigente el día de la clase");
        }
        boolean already = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM class_bookings
                                WHERE class_id = :cls AND member_id = :member AND status = 'confirmed')""")
            .param("cls", classId).param("member", memberId).query(Boolean.class).single();
        if (already) {
            throw new Rejected("ALREADY_BOOKED", "El cliente ya tiene una reserva confirmada en esta clase");
        }
        long id;
        try {
            id = jdbc.sql("""
                    INSERT INTO class_bookings (organization_id, class_id, member_id, status)
                    VALUES (:org, :cls, :member, 'confirmed') RETURNING id""")
                .param("org", orgId).param("cls", classId).param("member", memberId)
                .query(Long.class).single();
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        return find(orgId, id).orElseThrow();
    }

    /** La carrera por el último cupo o por una reserva repetida la resuelve la base de datos. */
    private static RuntimeException translate(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("no tiene cupos")) {
            return new Rejected("CLASS_FULL", "La clase no tiene cupos disponibles");
        }
        if (message.contains("membresía válida")) {
            return new Rejected("NO_VALID_MEMBERSHIP", "El cliente no tiene una membresía vigente el día de la clase");
        }
        if (message.contains("cancelada")) {
            return new Rejected("CLASS_CANCELLED", "La clase está cancelada");
        }
        if (message.contains("ya inició")) {
            return new Rejected("CLASS_STARTED", "La clase ya inició: no admite nuevas reservas");
        }
        if (message.contains("uq_") || message.contains("duplicate key")) {
            return new Rejected("ALREADY_BOOKED", "El cliente ya tiene una reserva confirmada en esta clase");
        }
        return e;
    }

    /**
     * Cancela la reserva hasta el inicio de la clase y libera el cupo. {@code onlyMemberId} no es nulo cuando
     * quien cancela es un Cliente: solo puede cancelar las suyas.
     */
    @Transactional
    public Optional<Booking> cancel(long orgId, long bookingId, Long onlyMemberId) {
        Optional<Booking> current = find(orgId, bookingId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Booking b = current.get();
        if (onlyMemberId != null && b.memberId() != onlyMemberId) {
            throw new NotYours();
        }
        if (b.status().equals("cancelled")) {
            throw new Rejected("BOOKING_ALREADY_CANCELLED", "La reserva ya está cancelada");
        }
        if (!b.classStartsAt().isAfter(OffsetDateTime.now())) {
            throw new Rejected("CLASS_STARTED", "La clase ya inició: la reserva no se puede cancelar");
        }
        jdbc.sql("""
                UPDATE class_bookings SET status = 'cancelled', cancelled_at = now()
                 WHERE id = :id AND organization_id = :org""")
            .param("id", bookingId).param("org", orgId).update();
        return find(orgId, bookingId);
    }

    @Transactional(readOnly = true)
    public List<Booking> list(long orgId, Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE b.organization_id = :org");
        List<Object[]> params = new ArrayList<>();
        if (filter.memberId() != null) {
            where.append(" AND b.member_id = :member");
            params.add(new Object[] {"member", filter.memberId()});
        }
        if (filter.classId() != null) {
            where.append(" AND b.class_id = :cls");
            params.add(new Object[] {"cls", filter.classId()});
        }
        if (filter.status() != null) {
            where.append(" AND b.status = CAST(:status AS booking_status)");
            params.add(new Object[] {"status", filter.status()});
        }
        if (filter.trainerMemberId() != null) {
            where.append(" AND c.trainer_member_id = :trainer");
            params.add(new Object[] {"trainer", filter.trainerMemberId()});
        }
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT + where + " ORDER BY c.starts_at DESC, b.id DESC")
            .param("org", orgId);
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(BookingService::map).list();
    }

    @Transactional(readOnly = true)
    public Optional<Booking> find(long orgId, long id) {
        return jdbc.sql(SELECT + " WHERE b.id = :id AND b.organization_id = :org")
            .param("id", id).param("org", orgId).query(BookingService::map).optional();
    }

    /** Afiliación del usuario en el gimnasio; vacío si no está afiliado y activo. */
    @Transactional(readOnly = true)
    public Optional<Long> memberIdOf(long orgId, long userId) {
        return jdbc.sql("""
                SELECT id FROM organization_members
                 WHERE organization_id = :org AND user_id = :user AND is_active""")
            .param("org", orgId).param("user", userId).query(Long.class).optional();
    }

    private static Booking map(ResultSet rs, int row) throws SQLException {
        return new Booking(rs.getLong("id"), rs.getLong("class_id"), rs.getString("class_name"),
            rs.getObject("class_starts_at", OffsetDateTime.class), rs.getString("branch_name"),
            rs.getLong("member_id"), rs.getString("member_name"), rs.getString("status"),
            rs.getObject("booked_at", OffsetDateTime.class), rs.getObject("cancelled_at", OffsetDateTime.class));
    }
}

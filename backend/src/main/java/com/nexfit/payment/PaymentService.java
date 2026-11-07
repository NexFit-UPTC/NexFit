package com.nexfit.payment;

import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pagos de un gimnasio (US-26 NEX-45, US-28 NEX-51, US-27 NEX-50, US-50 NEX-54). Toda consulta lleva
 * {@code organization_id}: un pago de otro gimnasio es indistinguible de uno inexistente (RN-20).
 * No hay borrado ni integración con pasarelas (RN-19, RN-31); los datos del pago no se modifican
 * (RN-40, lo refuerza un trigger) y solo un pago pendiente cambia de estado.
 *
 * <p>Este servicio es la API pública del módulo: la contratación de membresías (US-20) registra su
 * pago por aquí para que ambas cosas ocurran en la misma transacción.
 */
@Service
public class PaymentService {

    private static final String COLUMNS = """
        p.id, p.member_id, p.membership_id, p.concept, p.amount, p.paid_at, p.method::text AS method,
        p.reference, p.status::text AS status, p.void_reason, p.completed_at,
        u.first_name AS member_first_name, u.last_name AS member_last_name""";
    private static final String FROM = """
        FROM payments p
        JOIN organization_members om ON om.id = p.member_id
        JOIN users u ON u.id = om.user_id""";

    private final JdbcClient jdbc;
    private final AuditService audit;

    PaymentService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Payment(long id, long memberId, String memberName, Long membershipId, String concept,
                          BigDecimal amount, Instant paidAt, String method, String reference, String status,
                          String voidReason, Instant completedAt) { }

    /** Datos de un pago nuevo, ya validados por el controlador. */
    public record NewPayment(long memberId, Long membershipId, String concept, BigDecimal amount,
                             Instant paidAt, String method, String reference, boolean completed) { }

    public record Page(List<Payment> items, long total, BigDecimal completedTotal, int page, int size) { }

    public record Filter(Long memberId, String status, Instant from, Instant to) { }

    /** El afiliado no existe en el gimnasio o no tiene el rol Cliente. */
    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException() {
            super(null, null, false, false);
        }
    }

    /** La membresía no existe en el gimnasio o pertenece a otro cliente. */
    public static class MembershipMismatchException extends RuntimeException {
        public MembershipMismatchException() {
            super(null, null, false, false);
        }
    }

    /** El pago ya no está pendiente: completado y anulado son estados finales (RN-40). */
    public static class PaymentNotPendingException extends RuntimeException {
        public PaymentNotPendingException() {
            super(null, null, false, false);
        }
    }

    /** Registra el pago; si llega completado se fija quién y cuándo lo completó (D-04). */
    @Transactional
    public Payment register(long organizationId, long actorMemberId, NewPayment data) {
        boolean isClient = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM member_roles r
                                WHERE r.member_id = :member AND r.organization_id = :org AND r.role = 'client')""")
            .param("member", data.memberId()).param("org", organizationId)
            .query(Boolean.class).single();
        if (!isClient) {
            throw new ClientNotFoundException();
        }
        if (data.membershipId() != null) {
            boolean ownMembership = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM memberships
                                    WHERE id = :id AND organization_id = :org AND member_id = :member)""")
                .param("id", data.membershipId()).param("org", organizationId).param("member", data.memberId())
                .query(Boolean.class).single();
            if (!ownMembership) {
                throw new MembershipMismatchException();
            }
        }
        long id = jdbc.sql("""
                INSERT INTO payments (organization_id, member_id, membership_id, registered_by_member_id, concept,
                                      amount, paid_at, method, reference, status, completed_at, completed_by_member_id)
                VALUES (:org, :member, :membership, :actor, :concept, :amount, :paidAt,
                        CAST(:method AS payment_method), :reference, CAST(:status AS payment_status),
                        CASE WHEN :completed THEN now() END, CASE WHEN :completed THEN CAST(:actor AS bigint) END)
                RETURNING id""")
            .param("org", organizationId).param("member", data.memberId())
            .param("membership", data.membershipId()).param("actor", actorMemberId)
            .param("concept", data.concept()).param("amount", data.amount())
            .param("paidAt", java.sql.Timestamp.from(data.paidAt())).param("method", data.method())
            .param("reference", data.reference())
            .param("status", data.completed() ? "completed" : "pending").param("completed", data.completed())
            .query(Long.class).single();
        return find(organizationId, id).orElseThrow();
    }

    /** Pagos del gimnasio con filtros opcionales; el total solo suma los completados (RN-40). */
    @Transactional(readOnly = true)
    public Page list(long organizationId, Filter filter, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        params.put("org", organizationId);
        StringBuilder where = new StringBuilder(" WHERE p.organization_id = :org");
        if (filter.memberId() != null) {
            where.append(" AND p.member_id = :member");
            params.put("member", filter.memberId());
        }
        if (filter.status() != null) {
            where.append(" AND p.status = CAST(:status AS payment_status)");
            params.put("status", filter.status());
        }
        if (filter.from() != null) {
            where.append(" AND p.paid_at >= :from");
            params.put("from", java.sql.Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND p.paid_at < :to");
            params.put("to", java.sql.Timestamp.from(filter.to()));
        }
        long total = jdbc.sql("SELECT count(*) " + FROM + where).params(params).query(Long.class).single();
        BigDecimal completedTotal = jdbc.sql("SELECT COALESCE(sum(p.amount), 0) " + FROM + where
                + " AND p.status = 'completed'").params(params).query(BigDecimal.class).single();
        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("limit", size);
        pageParams.put("offset", (long) page * size);
        List<Payment> items = jdbc.sql("SELECT " + COLUMNS + " " + FROM + where
                + " ORDER BY p.paid_at DESC, p.id DESC LIMIT :limit OFFSET :offset")
            .params(pageParams).query(PaymentService::map).list();
        return new Page(items, total, completedTotal, page, size);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> find(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE p.id = :id AND p.organization_id = :org")
            .param("id", id).param("org", organizationId).query(PaymentService::map).optional();
    }

    /** Anula un pago pendiente con motivo y lo audita (US-27). Vacío si no existe en el gimnasio. */
    @Transactional
    public Optional<Payment> voidPayment(long organizationId, long actorUserId, long id, String reason, String ip) {
        Optional<Payment> current = find(organizationId, id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (!"pending".equals(current.get().status())) {
            throw new PaymentNotPendingException();
        }
        try {
            jdbc.sql("""
                    UPDATE payments SET status = 'voided', void_reason = :reason
                     WHERE id = :id AND organization_id = :org AND status = 'pending'""")
                .param("reason", reason).param("id", id).param("org", organizationId).update();
        } catch (DataIntegrityViolationException e) {
            throw new PaymentNotPendingException();
        }
        audit.record(AuditEventType.PAYMENT_VOIDED, actorUserId, organizationId, "payment", id, ip,
            Map.of("reason", reason));
        return find(organizationId, id);
    }

    /** Completa un pago pendiente fijando fecha y autor, y lo audita (US-50). */
    @Transactional
    public Optional<Payment> complete(long organizationId, long actorUserId, long actorMemberId, long id, String ip) {
        Optional<Payment> current = find(organizationId, id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (!"pending".equals(current.get().status())) {
            throw new PaymentNotPendingException();
        }
        jdbc.sql("""
                UPDATE payments SET status = 'completed', completed_at = now(), completed_by_member_id = :actor
                 WHERE id = :id AND organization_id = :org AND status = 'pending'""")
            .param("actor", actorMemberId).param("id", id).param("org", organizationId).update();
        audit.record(AuditEventType.PENDING_PAYMENT_COMPLETED, actorUserId, organizationId, "payment", id, ip, null);
        return find(organizationId, id);
    }

    /** Zona horaria del gimnasio (RN-41): los rangos de fechas se leen en ella, no en la del servidor. */
    @Transactional(readOnly = true)
    public java.time.ZoneId zoneOf(long organizationId) {
        return jdbc.sql("SELECT timezone FROM organizations WHERE id = :org").param("org", organizationId)
            .query(String.class).optional().map(java.time.ZoneId::of).orElse(java.time.ZoneId.of("America/Bogota"));
    }

    /** Id de la afiliación del usuario en el gimnasio (organization_members.id). */
    @Transactional(readOnly = true)
    public Optional<Long> memberIdOf(long organizationId, long userId) {
        return jdbc.sql("SELECT id FROM organization_members WHERE organization_id = :org AND user_id = :user")
            .param("org", organizationId).param("user", userId).query(Long.class).optional();
    }

    private static Payment map(ResultSet rs, int row) throws SQLException {
        long membership = rs.getLong("membership_id");
        Long membershipId = rs.wasNull() ? null : membership;
        java.sql.Timestamp completed = rs.getTimestamp("completed_at");
        return new Payment(rs.getLong("id"), rs.getLong("member_id"),
            rs.getString("member_first_name") + " " + rs.getString("member_last_name"), membershipId,
            rs.getString("concept"), rs.getBigDecimal("amount"), rs.getTimestamp("paid_at").toInstant(),
            rs.getString("method"), rs.getString("reference"), rs.getString("status"),
            rs.getString("void_reason"), completed == null ? null : completed.toInstant());
    }
}

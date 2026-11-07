package com.nexfit.membership;

import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import com.nexfit.payment.PaymentService;
import com.nexfit.payment.PaymentService.NewPayment;
import com.nexfit.payment.PaymentService.Payment;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membresías de los clientes (US-20 a US-25). Contratar y renovar crean la membresía y, si se pide, su pago
 * en la misma transacción (RNF-09). El estado efectivo se calcula con la fecha local del gimnasio (RN-27):
 * "vencida" nunca se guarda. Toda consulta lleva {@code organization_id} (RN-11).
 */
@Service
class MembershipService {

    /** Estados efectivos: scheduled, current, expired, suspended, cancelled. */
    static final String EFFECTIVE = """
        CASE WHEN m.status = 'cancelled' THEN 'cancelled'
             WHEN m.status = 'suspended' THEN 'suspended'
             WHEN m.start_date > fn_org_local_date(m.organization_id, now()) THEN 'scheduled'
             WHEN m.end_date < fn_org_local_date(m.organization_id, now()) THEN 'expired'
             ELSE 'current' END""";

    private static final String SELECT = """
        SELECT m.id, m.member_id, u.first_name || ' ' || u.last_name AS member_name, m.plan_id, p.name AS plan_name,
               m.start_date, m.end_date, m.status, %s AS effective_status, m.status_reason, m.status_changed_at,
               m.created_at
          FROM memberships m
          JOIN organization_members om ON om.id = m.member_id
          JOIN users u ON u.id = om.user_id
          JOIN membership_plans p ON p.id = m.plan_id
        """.formatted(EFFECTIVE);

    private final JdbcClient jdbc;
    private final PaymentService payments;
    private final AuditService audit;

    MembershipService(JdbcClient jdbc, PaymentService payments, AuditService audit) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.audit = audit;
    }

    public record Membership(long id, long memberId, String memberName, long planId, String planName,
                             LocalDate startDate, LocalDate endDate, String status, String effectiveStatus,
                             String statusReason, Instant statusChangedAt, Instant createdAt) { }

    /** Pago opcional de la contratación; el valor, si falta, es el precio del plan. */
    public record PaymentData(BigDecimal amount, String method, String reference, boolean completed) { }

    /** Membresía creada y el pago que se registró con ella (nulo si no hubo). */
    public record Contract(Membership membership, Payment payment) { }

    public record Filter(Long memberId, String effectiveStatus, Integer expiringInDays) { }

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException() {
            super(null, null, false, false);
        }
    }

    public static class PlanUnavailableException extends RuntimeException {
        public PlanUnavailableException() {
            super(null, null, false, false);
        }
    }

    public static class StartInPastException extends RuntimeException {
        public StartInPastException() {
            super(null, null, false, false);
        }
    }

    public static class OverlapException extends RuntimeException {
        public OverlapException() {
            super(null, null, false, false);
        }
    }

    /** La membresía no está en un estado desde el cual se pueda hacer la operación. */
    public static class InvalidStateException extends RuntimeException {
        private final String code;

        public InvalidStateException(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Contrata un plan para el cliente con inicio hoy o posterior; opcionalmente registra el pago. */
    @Transactional
    public Contract contract(long orgId, long actorMemberId, long memberId, long planId, LocalDate start,
                             PaymentData payment) {
        if (!isClient(orgId, memberId)) {
            throw new ClientNotFoundException();
        }
        LocalDate today = today(orgId);
        LocalDate startDate = start == null ? today : start;
        if (startDate.isBefore(today)) {
            throw new StartInPastException();
        }
        Plan plan = activePlan(orgId, planId);
        return create(orgId, actorMemberId, memberId, plan, startDate, payment, "Membresía " + plan.name);
    }

    /** Renueva una membresía vigente: la nueva inicia el día siguiente al vencimiento (D-06). */
    @Transactional
    public Optional<Contract> renew(long orgId, long actorMemberId, long membershipId, Long planId,
                                    PaymentData payment) {
        Optional<Membership> current = find(orgId, membershipId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Membership m = current.get();
        if (!m.effectiveStatus().equals("current")) {
            throw new InvalidStateException("MEMBERSHIP_NOT_RENEWABLE",
                "Solo se renueva una membresía vigente; si ya venció, tramita una nueva contratación");
        }
        Plan plan = activePlan(orgId, planId == null ? m.planId() : planId);
        return Optional.of(create(orgId, actorMemberId, m.memberId(), plan, m.endDate().plusDays(1), payment,
            "Renovación " + plan.name));
    }

    private Contract create(long orgId, long actorMemberId, long memberId, Plan plan, LocalDate start,
                            PaymentData payment, String concept) {
        LocalDate end = start.plusDays(plan.durationDays - 1L);
        boolean overlaps = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM memberships
                                WHERE member_id = :member AND organization_id = :org
                                  AND status IN ('active', 'suspended')
                                  AND daterange(start_date, end_date, '[]') && daterange(:s, :e, '[]'))""")
            .param("member", memberId).param("org", orgId).param("s", start).param("e", end)
            .query(Boolean.class).single();
        if (overlaps) {
            throw new OverlapException();
        }
        long id;
        try {
            id = jdbc.sql("""
                    INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id,
                                             start_date, end_date, status)
                    VALUES (:org, :member, :plan, :actor, :s, :e, 'active') RETURNING id""")
                .param("org", orgId).param("member", memberId).param("plan", plan.id)
                .param("actor", actorMemberId).param("s", start).param("e", end)
                .query(Long.class).single();
        } catch (DataIntegrityViolationException e) {
            // Carrera con otra contratación (restricción de exclusión) o cliente inactivo (trigger).
            if (String.valueOf(e.getMessage()).contains("ex_memberships_no_overlap")) {
                throw new OverlapException();
            }
            throw new ClientNotFoundException();
        }
        Payment paid = null;
        if (payment != null) {
            BigDecimal amount = payment.amount() == null ? plan.price : payment.amount();
            paid = payments.register(orgId, actorMemberId, new NewPayment(memberId, id, concept, amount,
                Instant.now(), payment.method(), payment.reference(), payment.completed()));
        }
        return new Contract(find(orgId, id).orElseThrow(), paid);
    }

    /** Membresías del gimnasio con filtros; "expiring" son las vigentes que vencen de hoy a hoy + N (RN-27). */
    @Transactional(readOnly = true)
    public List<Membership> list(long orgId, Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE m.organization_id = :org");
        if (filter.memberId() != null) {
            where.append(" AND m.member_id = :member");
        }
        if (filter.effectiveStatus() != null) {
            where.append(" AND ").append(EFFECTIVE).append(" = :eff");
        }
        if (filter.expiringInDays() != null) {
            where.append(" AND m.status = 'active'"
                + " AND m.end_date BETWEEN fn_org_local_date(m.organization_id, now())"
                + " AND fn_org_local_date(m.organization_id, now()) + :days"
                + " AND m.start_date <= fn_org_local_date(m.organization_id, now())");
        }
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT + where + " ORDER BY m.start_date DESC, m.id DESC")
            .param("org", orgId);
        if (filter.memberId() != null) {
            spec = spec.param("member", filter.memberId());
        }
        if (filter.effectiveStatus() != null) {
            spec = spec.param("eff", filter.effectiveStatus());
        }
        if (filter.expiringInDays() != null) {
            spec = spec.param("days", filter.expiringInDays());
        }
        return spec.query(MembershipService::map).list();
    }

    @Transactional(readOnly = true)
    public Optional<Membership> find(long orgId, long id) {
        return jdbc.sql(SELECT + " WHERE m.id = :id AND m.organization_id = :org")
            .param("id", id).param("org", orgId).query(MembershipService::map).optional();
    }

    /** Suspende una membresía activa y cancela las reservas futuras del cliente (US-25). */
    @Transactional
    public Optional<Membership> suspend(long orgId, long actorUserId, long actorMemberId, long id, String reason,
                                        String ip) {
        Optional<Membership> current = find(orgId, id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (!current.get().status().equals("active")) {
            throw new InvalidStateException("MEMBERSHIP_NOT_ACTIVE", "Solo se suspende una membresía activa");
        }
        changeStatus(orgId, id, "suspended", reason, actorMemberId);
        int bookings = cancelFutureBookings(orgId, current.get().memberId());
        audit.record(AuditEventType.MEMBERSHIP_SUSPENDED, actorUserId, orgId, "membership", id, ip,
            Map.of("reason", reason, "cancelledBookings", bookings));
        return find(orgId, id);
    }

    /** Reactiva una suspendida que no ha vencido; conserva el vencimiento (US-25). */
    @Transactional
    public Optional<Membership> reactivate(long orgId, long actorUserId, long actorMemberId, long id, String reason,
                                           String ip) {
        Optional<Membership> current = find(orgId, id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Membership m = current.get();
        if (!m.status().equals("suspended")) {
            throw new InvalidStateException("MEMBERSHIP_NOT_SUSPENDED", "Solo se reactiva una membresía suspendida");
        }
        if (m.endDate().isBefore(today(orgId))) {
            throw new InvalidStateException("MEMBERSHIP_EXPIRED",
                "La membresía ya venció: tramita una nueva contratación");
        }
        try {
            changeStatus(orgId, id, "active", reason, actorMemberId);
        } catch (DataIntegrityViolationException e) {
            throw new OverlapException();
        }
        audit.record(AuditEventType.MEMBERSHIP_REACTIVATED, actorUserId, orgId, "membership", id, ip,
            Map.of("reason", reason));
        return find(orgId, id);
    }

    /** Cancela una membresía activa o suspendida; es definitivo (US-24). */
    @Transactional
    public Optional<Membership> cancel(long orgId, long actorUserId, long actorMemberId, long id, String reason,
                                       String ip) {
        Optional<Membership> current = find(orgId, id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (current.get().status().equals("cancelled")) {
            throw new InvalidStateException("MEMBERSHIP_CANCELLED", "La membresía ya está cancelada");
        }
        changeStatus(orgId, id, "cancelled", reason, actorMemberId);
        int bookings = cancelFutureBookings(orgId, current.get().memberId());
        audit.record(AuditEventType.MEMBERSHIP_CANCELLED, actorUserId, orgId, "membership", id, ip,
            Map.of("reason", reason, "cancelledBookings", bookings));
        return find(orgId, id);
    }

    private void changeStatus(long orgId, long id, String status, String reason, long actorMemberId) {
        jdbc.sql("""
                UPDATE memberships
                   SET status = CAST(:status AS membership_status), status_reason = :reason,
                       status_changed_at = now(), status_changed_by_member_id = :actor
                 WHERE id = :id AND organization_id = :org""")
            .param("status", status).param("reason", reason).param("actor", actorMemberId)
            .param("id", id).param("org", orgId).update();
    }

    /** Reservas confirmadas de clases que aún no empiezan (RN-30). */
    private int cancelFutureBookings(long orgId, long memberId) {
        return jdbc.sql("""
                UPDATE class_bookings b SET status = 'cancelled', cancelled_at = now()
                 WHERE b.organization_id = :org AND b.member_id = :member AND b.status = 'confirmed'
                   AND EXISTS (SELECT 1 FROM classes c WHERE c.id = b.class_id AND c.starts_at > now())""")
            .param("org", orgId).param("member", memberId).update();
    }

    /** Fecha de hoy en la zona horaria del gimnasio (RN-27). */
    @Transactional(readOnly = true)
    public LocalDate today(long orgId) {
        return jdbc.sql("SELECT fn_org_local_date(:org, now())").param("org", orgId)
            .query(LocalDate.class).single();
    }

    /** Afiliado del gimnasio con rol Cliente. */
    private boolean isClient(long orgId, long memberId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM member_roles r
                                WHERE r.member_id = :member AND r.organization_id = :org AND r.role = 'client')""")
            .param("member", memberId).param("org", orgId).query(Boolean.class).single();
    }

    private record Plan(long id, String name, BigDecimal price, int durationDays) { }

    private Plan activePlan(long orgId, long planId) {
        return jdbc.sql("""
                SELECT id, name, price, duration_days FROM membership_plans
                 WHERE id = :id AND organization_id = :org AND is_active""")
            .param("id", planId).param("org", orgId)
            .query((rs, i) -> new Plan(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("price"),
                rs.getInt("duration_days")))
            .optional().orElseThrow(PlanUnavailableException::new);
    }

    private static Membership map(ResultSet rs, int row) throws SQLException {
        var changed = rs.getTimestamp("status_changed_at");
        return new Membership(rs.getLong("id"), rs.getLong("member_id"), rs.getString("member_name"),
            rs.getLong("plan_id"), rs.getString("plan_name"), rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class), rs.getString("status"), rs.getString("effective_status"),
            rs.getString("status_reason"), changed == null ? null : changed.toInstant(),
            rs.getTimestamp("created_at").toInstant());
    }
}

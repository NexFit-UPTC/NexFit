package com.nexfit.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indicadores del panel del Administrador (US-45) y cifras globales del Master (US-46). Todo se calcula con
 * consultas agregadas acotadas por {@code organization_id} y por la fecha local del gimnasio (RN-41).
 */
@Service
class DashboardService {

    /** Días hacia adelante de «por vencer» y de la ocupación de clases (D-16). */
    static final int HORIZON_DAYS = 7;

    private final JdbcClient jdbc;

    DashboardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Occupancy(int classes, long confirmedBookings, long capacity, BigDecimal percent) { }

    public record GymPanel(LocalDate date, String timezone, long activeClients, long currentMemberships,
                           long expiringMemberships, int expiringInDays, BigDecimal monthRevenue,
                           long todayCheckIns, Occupancy upcomingOccupancy) { }

    public record PlatformFigures(long gyms, long branches, long clients, long currentMemberships) { }

    @Transactional(readOnly = true)
    public GymPanel gym(long orgId) {
        String tz = jdbc.sql("SELECT timezone FROM organizations WHERE id = :org").param("org", orgId)
            .query(String.class).single();
        LocalDate today = LocalDate.now(ZoneId.of(tz));
        LocalDate soon = today.plusDays(HORIZON_DAYS);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate nextMonth = monthStart.plusMonths(1);

        long clients = count("""
            SELECT count(*) FROM organization_members om JOIN users u ON u.id = om.user_id
             WHERE om.organization_id = :org AND om.is_active AND u.is_active
               AND EXISTS (SELECT 1 FROM member_roles r WHERE r.member_id = om.id AND r.role = 'client')""", orgId);
        long current = jdbc.sql("""
                SELECT count(*) FROM memberships
                 WHERE organization_id = :org AND status = 'active' AND :today BETWEEN start_date AND end_date""")
            .param("org", orgId).param("today", today).query(Long.class).single();
        long expiring = jdbc.sql("""
                SELECT count(*) FROM memberships
                 WHERE organization_id = :org AND status = 'active' AND start_date <= :today
                   AND end_date BETWEEN :today AND :soon""")
            .param("org", orgId).param("today", today).param("soon", soon).query(Long.class).single();
        // Solo pagos completados (RN-20): los pendientes y los anulados no cuentan como ingreso.
        BigDecimal revenue = jdbc.sql("""
                SELECT coalesce(sum(amount), 0) FROM payments
                 WHERE organization_id = :org AND status = 'completed'
                   AND (paid_at AT TIME ZONE :tz)::date >= :start AND (paid_at AT TIME ZONE :tz)::date < :end""")
            .param("org", orgId).param("tz", tz).param("start", monthStart).param("end", nextMonth)
            .query(BigDecimal.class).single();
        long checkIns = jdbc.sql("""
                SELECT count(*) FROM check_ins
                 WHERE organization_id = :org AND (check_in_at AT TIME ZONE :tz)::date = :today""")
            .param("org", orgId).param("tz", tz).param("today", today).query(Long.class).single();
        return new GymPanel(today, tz, clients, current, expiring, HORIZON_DAYS, revenue, checkIns, occupancy(orgId));
    }

    /** Reservas confirmadas / capacidad de las clases programadas que empiezan en los próximos 7 días. */
    private Occupancy occupancy(long orgId) {
        return jdbc.sql("""
                SELECT count(*) AS classes, coalesce(sum(t.capacity), 0) AS capacity,
                       coalesce(sum(t.confirmed), 0) AS confirmed
                  FROM (SELECT c.capacity,
                               (SELECT count(*) FROM class_bookings b
                                 WHERE b.class_id = c.id AND b.status = 'confirmed') AS confirmed
                          FROM classes c
                         WHERE c.organization_id = :org AND c.status = 'scheduled'
                           AND c.starts_at >= now() AND c.starts_at < now() + make_interval(days => :days)) t""")
            .param("org", orgId).param("days", HORIZON_DAYS)
            .query((rs, n) -> {
                long capacity = rs.getLong("capacity");
                long confirmed = rs.getLong("confirmed");
                BigDecimal percent = capacity == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(confirmed * 100).divide(BigDecimal.valueOf(capacity), 1, RoundingMode.HALF_UP);
                return new Occupancy(rs.getInt("classes"), confirmed, capacity, percent);
            }).single();
    }

    /** Cifras agregadas de toda la plataforma, sin listar personas (US-46). */
    @Transactional(readOnly = true)
    public PlatformFigures platform() {
        long gyms = jdbc.sql("SELECT count(*) FROM organizations WHERE is_active").query(Long.class).single();
        long branches = jdbc.sql("""
                SELECT count(*) FROM branches b JOIN organizations o ON o.id = b.organization_id
                 WHERE b.is_active AND o.is_active""").query(Long.class).single();
        long clients = jdbc.sql("""
                SELECT count(*) FROM organization_members om
                  JOIN users u ON u.id = om.user_id JOIN organizations o ON o.id = om.organization_id
                 WHERE om.is_active AND u.is_active AND o.is_active
                   AND EXISTS (SELECT 1 FROM member_roles r WHERE r.member_id = om.id AND r.role = 'client')""")
            .query(Long.class).single();
        long memberships = jdbc.sql("""
                SELECT count(*) FROM memberships m JOIN organizations o ON o.id = m.organization_id
                 WHERE o.is_active AND m.status = 'active'
                   AND fn_org_local_date(m.organization_id, now()) BETWEEN m.start_date AND m.end_date""")
            .query(Long.class).single();
        return new PlatformFigures(gyms, branches, clients, memberships);
    }

    private long count(String sql, long orgId) {
        return jdbc.sql(sql).param("org", orgId).query(Long.class).single();
    }
}

package com.nexfit.administration.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consulta de clientes del gimnasio (US-15, NEX-43). Toda consulta lleva {@code organization_id}; el
 * estado de la membresía se calcula con la fecha local del establecimiento, no con la del servidor.
 * Debe ser public: el servicio vive en el paquete padre.
 */
@Repository
public class ClientQueryRepository {

    /**
     * Membresía "efectiva" del cliente en este gimnasio, una sola: la vigente, si no la suspendida, la
     * programada, la vencida más reciente y por último la cancelada. Estados: active, suspended,
     * scheduled, expired, cancelled.
     */
    private static final String LATERAL_MEMBERSHIP = """
        LEFT JOIN LATERAL (
          SELECT x.plan_id, x.start_date, x.end_date, x.effective_status
            FROM (SELECT m.plan_id, m.start_date, m.end_date,
                         CASE WHEN m.status = 'cancelled' THEN 'cancelled'
                              WHEN m.status = 'suspended' THEN 'suspended'
                              WHEN fn_org_local_date(om.organization_id, now()) < m.start_date THEN 'scheduled'
                              WHEN fn_org_local_date(om.organization_id, now()) > m.end_date THEN 'expired'
                              ELSE 'active' END AS effective_status
                    FROM memberships m
                   WHERE m.member_id = om.id AND m.organization_id = om.organization_id) x
           ORDER BY CASE x.effective_status WHEN 'active' THEN 1 WHEN 'suspended' THEN 2
                                            WHEN 'scheduled' THEN 3 WHEN 'expired' THEN 4 ELSE 5 END,
                    x.end_date DESC
           LIMIT 1) ms ON true
        """;

    private static final String IS_CLIENT = """
        EXISTS (SELECT 1 FROM member_roles mr WHERE mr.member_id = om.id
                AND mr.role = CAST('client' AS member_role))""";

    private final JdbcClient jdbc;

    ClientQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Summary(long memberId, String documentType, String documentNumber, String firstName,
                          String lastName, boolean active, String membershipStatus, LocalDate membershipEndDate) { }

    public record Membership(String status, String planName, LocalDate startDate, LocalDate endDate) { }

    public record Detail(long memberId, String documentType, String documentNumber, String firstName,
                         String lastName, String email, LocalDate birthDate, boolean active,
                         OffsetDateTime joinedAt, Membership membership, String phone, Long epsId,
                         String epsName) { }

    public record Page(List<Summary> content, long total) { }

    /** El actor es Staff o Administrador activo de este gimnasio. */
    public boolean isStaffOrAdmin(long orgId, long userId) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM organization_members om
                  JOIN member_roles mr ON mr.member_id = om.id
                  WHERE om.organization_id = :org AND om.user_id = :user AND om.is_active
                    AND mr.role IN (CAST('staff' AS member_role), CAST('admin' AS member_role)))
                """)
            .param("org", orgId).param("user", userId)
            .query((rs, n) -> rs.getBoolean(1)).single();
    }

    /**
     * Clientes del gimnasio por apellido y nombre. {@code tokens} son fragmentos ya escapados: cada uno debe
     * aparecer en el nombre completo o en el documento. {@code active} nulo trae todos.
     */
    public Page search(long orgId, List<String> tokens, Boolean active, int limit, long offset) {
        StringBuilder where = new StringBuilder(" WHERE om.organization_id = :org AND " + IS_CLIENT);
        if (active != null) {
            where.append(" AND om.is_active = :active");
        }
        for (int i = 0; i < tokens.size(); i++) {
            where.append(" AND (u.first_name || ' ' || u.last_name || ' ' || u.document_number)"
                + " ILIKE :t" + i + " ESCAPE '\\'");
        }
        String from = " FROM organization_members om JOIN users u ON u.id = om.user_id ";

        JdbcClient.StatementSpec count = jdbc.sql("SELECT count(*)" + from + where).param("org", orgId);
        JdbcClient.StatementSpec rows = jdbc.sql("""
                SELECT om.id AS member_id, CAST(u.document_type AS text) AS document_type, u.document_number,
                       u.first_name, u.last_name, om.is_active, ms.effective_status, ms.end_date
                """ + from + LATERAL_MEMBERSHIP + where
                + " ORDER BY lower(u.last_name), lower(u.first_name), om.id LIMIT :limit OFFSET :offset")
            .param("org", orgId).param("limit", limit).param("offset", offset);
        if (active != null) {
            count = count.param("active", active);
            rows = rows.param("active", active);
        }
        List<String> patterns = new ArrayList<>();
        for (String token : tokens) {
            patterns.add("%" + token + "%");
        }
        for (int i = 0; i < patterns.size(); i++) {
            count = count.param("t" + i, patterns.get(i));
            rows = rows.param("t" + i, patterns.get(i));
        }
        long total = count.query(Long.class).single();
        List<Summary> content = rows.query((rs, n) -> new Summary(rs.getLong("member_id"),
            rs.getString("document_type"), rs.getString("document_number"), rs.getString("first_name"),
            rs.getString("last_name"), rs.getBoolean("is_active"), rs.getString("effective_status"),
            rs.getObject("end_date", LocalDate.class))).list();
        return new Page(content, total);
    }

    /** El cliente (activo o no) de este gimnasio; vacío si no existe, no es cliente o es de otro gimnasio. */
    public Optional<Detail> findDetail(long orgId, long memberId) {
        return jdbc.sql("""
                SELECT om.id AS member_id, CAST(u.document_type AS text) AS document_type, u.document_number,
                       u.first_name, u.last_name, u.email, u.birth_date, om.is_active, om.joined_at,
                       om.phone, om.eps_id, e.name AS eps_name,
                       ms.effective_status, p.name AS plan_name, ms.start_date, ms.end_date
                  FROM organization_members om
                  JOIN users u ON u.id = om.user_id
                  LEFT JOIN health_insurance_providers e ON e.id = om.eps_id
                """ + LATERAL_MEMBERSHIP + """
                  LEFT JOIN membership_plans p ON p.id = ms.plan_id AND p.organization_id = om.organization_id
                 WHERE om.id = :member AND om.organization_id = :org AND\s""" + IS_CLIENT)
            .param("member", memberId).param("org", orgId)
            .query((rs, n) -> {
                String status = rs.getString("effective_status");
                Membership membership = status == null ? null : new Membership(status, rs.getString("plan_name"),
                    rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class));
                return new Detail(rs.getLong("member_id"), rs.getString("document_type"),
                    rs.getString("document_number"), rs.getString("first_name"), rs.getString("last_name"),
                    rs.getString("email"), rs.getObject("birth_date", LocalDate.class),
                    rs.getBoolean("is_active"), rs.getObject("joined_at", OffsetDateTime.class), membership,
                    rs.getString("phone"), rs.getObject("eps_id", Long.class), rs.getString("eps_name"));
            })
            .optional();
    }
}

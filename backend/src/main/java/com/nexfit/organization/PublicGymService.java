package com.nexfit.organization;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta pública de gimnasios (NEX-97): solo gimnasios activos y solo datos que puede ver cualquier
 * visitante (nombre, sedes activas con su dirección y planes activos). Nunca expone contacto, conteos
 * ni personal. Lee {@code membership_plans} por SQL propio para no depender de clases del módulo membership.
 */
@Service
class PublicGymService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final JdbcClient jdbc;

    PublicGymService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PublicBranch(String name, String addressLine1, String addressLine2, String city,
                               String department) { }

    public record PublicPlan(String name, BigDecimal price, int durationDays) { }

    public record GymSummary(long id, String name, List<PublicBranch> branches) { }

    public record GymDetail(long id, String name, List<PublicBranch> branches, List<PublicPlan> plans) { }

    public record GymPage(List<GymSummary> content, int page, int size, long totalElements, int totalPages) { }

    /** Página de gimnasios activos por nombre; {@code size} se acota a {@link #MAX_SIZE}. */
    @Transactional(readOnly = true)
    public GymPage list(int page, int size) {
        int pageSize = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int pageIndex = Math.max(page, 0);
        long total = jdbc.sql("SELECT count(*) FROM organizations WHERE is_active").query(Long.class).single();
        List<GymSummary> rows = jdbc.sql("SELECT id, name FROM organizations WHERE is_active"
                + " ORDER BY lower(name), id LIMIT :limit OFFSET :offset")
            .param("limit", pageSize).param("offset", (long) pageIndex * pageSize)
            .query((rs, n) -> new GymSummary(rs.getLong("id"), rs.getString("name"), List.of()))
            .list();
        Map<Long, List<PublicBranch>> branches = branchesOf(rows.stream().map(GymSummary::id).toList());
        List<GymSummary> content = rows.stream()
            .map(g -> new GymSummary(g.id(), g.name(), branches.getOrDefault(g.id(), List.of())))
            .toList();
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new GymPage(content, pageIndex, pageSize, total, totalPages);
    }

    /** Detalle del gimnasio activo; vacío si no existe o está inactivo. */
    @Transactional(readOnly = true)
    public Optional<GymDetail> find(long id) {
        return jdbc.sql("SELECT name FROM organizations WHERE id = :id AND is_active")
            .param("id", id).query(String.class).optional()
            .map(name -> new GymDetail(id, name, branchesOf(List.of(id)).getOrDefault(id, List.of()), plansOf(id)));
    }

    private Map<Long, List<PublicBranch>> branchesOf(List<Long> orgIds) {
        Map<Long, List<PublicBranch>> byOrg = new LinkedHashMap<>();
        if (orgIds.isEmpty()) {
            return byOrg;
        }
        jdbc.sql("SELECT organization_id, name, address_line1, address_line2, city, department FROM branches"
                + " WHERE organization_id IN (:ids) AND is_active ORDER BY lower(name), id")
            .param("ids", orgIds)
            .query((rs, n) -> {
                byOrg.computeIfAbsent(rs.getLong("organization_id"), k -> new ArrayList<>())
                    .add(new PublicBranch(rs.getString("name"), rs.getString("address_line1"),
                        rs.getString("address_line2"), rs.getString("city"), rs.getString("department")));
                return null;
            }).list();
        return byOrg;
    }

    private List<PublicPlan> plansOf(long orgId) {
        return jdbc.sql("SELECT name, price, duration_days FROM membership_plans"
                + " WHERE organization_id = :org AND is_active ORDER BY price, lower(name), id")
            .param("org", orgId)
            .query((rs, n) -> new PublicPlan(rs.getString("name"), rs.getBigDecimal("price"),
                rs.getInt("duration_days")))
            .list();
    }
}

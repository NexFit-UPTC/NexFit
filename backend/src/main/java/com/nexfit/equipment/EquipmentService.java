package com.nexfit.equipment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipo de cada sede (US-12, NEX-38). Estados: available, maintenance, out_of_service y retired, que es final.
 * Toda consulta lleva {@code organization_id}; las sedes de otro gimnasio se rechazan (RN-21).
 */
@Service
class EquipmentService {

    private static final String SELECT = """
        SELECT e.id, e.branch_id, b.name AS branch_name, e.name, e.category, e.status::text AS status,
               e.acquisition_date, e.image_url
          FROM equipment e JOIN branches b ON b.id = e.branch_id
        """;

    private final JdbcClient jdbc;

    EquipmentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Equipment(long id, long branchId, String branchName, String name, String category, String status,
                            LocalDate acquisitionDate, String imageUrl) { }

    public record EquipmentData(String name, String category, LocalDate acquisitionDate, String imageUrl) { }

    public record Filter(Long branchId, String status, String category) { }

    /** La sede no existe en el gimnasio o está inactiva (400). */
    static class InvalidBranchException extends RuntimeException {
        InvalidBranchException() {
            super(null, null, false, false);
        }
    }

    /** El equipo dado de baja no admite cambios de estado (409). */
    static class RetiredException extends RuntimeException {
        RetiredException() {
            super(null, null, false, false);
        }
    }

    @Transactional
    public Equipment create(long orgId, long branchId, EquipmentData data, String status) {
        boolean ok = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM branches WHERE id = :id AND organization_id = :org AND is_active)""")
            .param("id", branchId).param("org", orgId).query(Boolean.class).single();
        if (!ok) {
            throw new InvalidBranchException();
        }
        long id = jdbc.sql("""
                INSERT INTO equipment (organization_id, branch_id, name, category, status, acquisition_date, image_url)
                VALUES (:org, :branch, :name, :category, CAST(:status AS equipment_status), :acquired, :image)
                RETURNING id""")
            .param("org", orgId).param("branch", branchId).param("name", data.name())
            .param("category", data.category(), java.sql.Types.VARCHAR)
            .param("status", status)
            .param("acquired", data.acquisitionDate(), java.sql.Types.DATE)
            .param("image", data.imageUrl(), java.sql.Types.VARCHAR)
            .query(Long.class).single();
        return find(orgId, id).orElseThrow();
    }

    @Transactional
    public Optional<Equipment> update(long orgId, long id, EquipmentData data) {
        int rows = jdbc.sql("""
                UPDATE equipment SET name = :name, category = :category, acquisition_date = :acquired,
                                     image_url = :image
                 WHERE id = :id AND organization_id = :org""")
            .param("name", data.name()).param("category", data.category(), java.sql.Types.VARCHAR)
            .param("acquired", data.acquisitionDate(), java.sql.Types.DATE)
            .param("image", data.imageUrl(), java.sql.Types.VARCHAR)
            .param("id", id).param("org", orgId).update();
        return rows == 0 ? Optional.empty() : find(orgId, id);
    }

    /** Cambia el estado. Un equipo dado de baja (retired) no cambia nunca más; repetir el mismo estado no falla. */
    @Transactional
    public Optional<Equipment> setStatus(long orgId, long id, String status) {
        Optional<String> current = jdbc.sql("""
                SELECT status::text FROM equipment WHERE id = :id AND organization_id = :org FOR UPDATE""")
            .param("id", id).param("org", orgId).query(String.class).optional();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (current.get().equals("retired") && !status.equals("retired")) {
            throw new RetiredException();
        }
        jdbc.sql("UPDATE equipment SET status = CAST(:status AS equipment_status) WHERE id = :id AND organization_id = :org")
            .param("status", status).param("id", id).param("org", orgId).update();
        return find(orgId, id);
    }

    @Transactional(readOnly = true)
    public List<Equipment> list(long orgId, Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE e.organization_id = :org");
        List<Object[]> params = new ArrayList<>();
        if (filter.branchId() != null) {
            where.append(" AND e.branch_id = :branch");
            params.add(new Object[] {"branch", filter.branchId()});
        }
        if (filter.status() != null) {
            where.append(" AND e.status = CAST(:status AS equipment_status)");
            params.add(new Object[] {"status", filter.status()});
        }
        if (filter.category() != null) {
            where.append(" AND lower(e.category) = lower(:category)");
            params.add(new Object[] {"category", filter.category()});
        }
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT + where + " ORDER BY b.name, e.name, e.id").param("org", orgId);
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(EquipmentService::map).list();
    }

    @Transactional(readOnly = true)
    public Optional<Equipment> find(long orgId, long id) {
        return jdbc.sql(SELECT + " WHERE e.id = :id AND e.organization_id = :org")
            .param("id", id).param("org", orgId).query(EquipmentService::map).optional();
    }

    private static Equipment map(ResultSet rs, int row) throws SQLException {
        return new Equipment(rs.getLong("id"), rs.getLong("branch_id"), rs.getString("branch_name"),
            rs.getString("name"), rs.getString("category"), rs.getString("status"),
            rs.getObject("acquisition_date", LocalDate.class), rs.getString("image_url"));
    }
}

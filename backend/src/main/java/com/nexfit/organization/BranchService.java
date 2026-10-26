package com.nexfit.organization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sedes de un gimnasio (US-10, NEX-35). Toda consulta lleva {@code organization_id}: una sede de otro
 * gimnasio es indistinguible de una inexistente (RN-20).
 *
 * <p>Desactivar una sede solo cambia {@code is_active}: las clases e ingresos previos se conservan.
 * El rechazo de ingresos y clases nuevas en una sede inactiva (RN-33) lo aplican esos flujos
 * (NEX-59 y NEX-40) leyendo este estado.
 */
@Service
class BranchService {

    private static final String COLUMNS = "id, name, phone, schedule, address_line1, address_line2, city,"
        + " department, postal_code, country, is_active";

    private final JdbcClient jdbc;

    BranchService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Branch(long id, String name, String phone, String schedule, String addressLine1,
                         String addressLine2, String city, String department, String postalCode,
                         String country, boolean active) { }

    /** Datos editables de una sede, ya validados por el controlador. */
    public record BranchData(String name, String phone, String schedule, String addressLine1,
                             String addressLine2, String city, String department, String postalCode,
                             String country) { }

    /** Ya existe una sede con ese nombre (sin distinguir mayúsculas) en el gimnasio. */
    static class BranchNameTakenException extends RuntimeException {
        BranchNameTakenException() {
            super(null, null, false, false);
        }
    }

    /** Crea la sede activa en el gimnasio. */
    @Transactional
    public Branch create(long organizationId, BranchData data) {
        try {
            return bind(jdbc.sql("""
                    INSERT INTO branches (organization_id, name, phone, schedule, address_line1, address_line2,
                                          city, department, postal_code, country, is_active)
                    VALUES (:org, :name, :phone, :schedule, :address1, :address2,
                            :city, :department, :postalCode, :country, true)
                    RETURNING """ + " " + COLUMNS), organizationId, data)
                .query(BranchService::map).single();
        } catch (DuplicateKeyException e) {
            throw new BranchNameTakenException();
        }
    }

    /** Sedes del gimnasio por nombre; {@code active} null devuelve todas. */
    @Transactional(readOnly = true)
    public List<Branch> list(long organizationId, Boolean active) {
        String filter = active == null ? "" : " AND is_active = :active";
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT " + COLUMNS + " FROM branches"
            + " WHERE organization_id = :org" + filter + " ORDER BY lower(name), id")
            .param("org", organizationId);
        if (active != null) {
            spec = spec.param("active", active);
        }
        return spec.query(BranchService::map).list();
    }

    /** La sede del gimnasio, activa o no; vacía si no existe o es de otro gimnasio. */
    @Transactional(readOnly = true)
    public Optional<Branch> find(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM branches WHERE id = :id AND organization_id = :org")
            .param("id", id).param("org", organizationId)
            .query(BranchService::map).optional();
    }

    /** Reemplaza los datos de la sede; no cambia su estado. Vacío si no existe en el gimnasio. */
    @Transactional
    public Optional<Branch> update(long organizationId, long id, BranchData data) {
        try {
            return bind(jdbc.sql("""
                    UPDATE branches
                       SET name = :name, phone = :phone, schedule = :schedule, address_line1 = :address1,
                           address_line2 = :address2, city = :city, department = :department,
                           postal_code = :postalCode, country = :country
                     WHERE id = :id AND organization_id = :org
                    RETURNING """ + " " + COLUMNS).param("id", id), organizationId, data)
                .query(BranchService::map).optional();
        } catch (DuplicateKeyException e) {
            throw new BranchNameTakenException();
        }
    }

    /** Activa o desactiva la sede (idempotente). Vacío si no existe en el gimnasio. */
    @Transactional
    public Optional<Branch> setActive(long organizationId, long id, boolean active) {
        return jdbc.sql("""
                UPDATE branches SET is_active = :active
                 WHERE id = :id AND organization_id = :org
                RETURNING """ + " " + COLUMNS)
            .param("id", id).param("org", organizationId).param("active", active)
            .query(BranchService::map).optional();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, long organizationId, BranchData d) {
        return spec.param("org", organizationId)
            .param("name", d.name()).param("phone", d.phone()).param("schedule", d.schedule())
            .param("address1", d.addressLine1()).param("address2", d.addressLine2())
            .param("city", d.city()).param("department", d.department())
            .param("postalCode", d.postalCode()).param("country", d.country());
    }

    private static Branch map(ResultSet rs, int row) throws SQLException {
        return new Branch(rs.getLong("id"), rs.getString("name"), rs.getString("phone"),
            rs.getString("schedule"), rs.getString("address_line1"), rs.getString("address_line2"),
            rs.getString("city"), rs.getString("department"), rs.getString("postal_code"),
            rs.getString("country"), rs.getBoolean("is_active"));
    }
}

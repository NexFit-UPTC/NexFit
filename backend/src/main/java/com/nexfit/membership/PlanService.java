package com.nexfit.membership;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planes de membresía de un gimnasio (US-19, NEX-37). Toda consulta lleva {@code organization_id}:
 * un plan de otro gimnasio es indistinguible de uno inexistente (RN-20).
 *
 * <p>Modificar o desactivar un plan solo toca {@code membership_plans}. Las membresías ya contratadas
 * guardan sus propias fechas y no se recalculan (RN-29); el precio vive en el pago, no en el plan.
 */
@Service
class PlanService {

    private static final String COLUMNS = "id, name, description, price, duration_days, benefits, is_active";

    private final JdbcClient jdbc;

    PlanService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Plan(long id, String name, String description, BigDecimal price, int durationDays,
                       String benefits, boolean active) { }

    /** Datos editables de un plan, ya validados por el controlador. */
    public record PlanData(String name, String description, BigDecimal price, int durationDays, String benefits) { }

    /** Ya existe un plan con ese nombre (sin distinguir mayúsculas) en el gimnasio. */
    static class PlanNameTakenException extends RuntimeException {
        PlanNameTakenException() {
            super(null, null, false, false);
        }
    }

    /** Crea el plan activo: queda disponible en el gimnasio desde ese momento. */
    @Transactional
    public Plan create(long organizationId, PlanData data) {
        try {
            return jdbc.sql("""
                    INSERT INTO membership_plans (organization_id, name, description, price, duration_days, benefits, is_active)
                    VALUES (:org, :name, :description, :price, :days, :benefits, true)
                    RETURNING """ + " " + COLUMNS)
                .param("org", organizationId)
                .param("name", data.name()).param("description", data.description())
                .param("price", data.price()).param("days", data.durationDays())
                .param("benefits", data.benefits())
                .query(PlanService::map).single();
        } catch (DuplicateKeyException e) {
            throw new PlanNameTakenException();
        }
    }

    /** Planes del gimnasio por nombre; {@code active} null devuelve todos. */
    @Transactional(readOnly = true)
    public List<Plan> list(long organizationId, Boolean active) {
        String filter = active == null ? "" : " AND is_active = :active";
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT " + COLUMNS + " FROM membership_plans"
            + " WHERE organization_id = :org" + filter + " ORDER BY lower(name), id")
            .param("org", organizationId);
        if (active != null) {
            spec = spec.param("active", active);
        }
        return spec.query(PlanService::map).list();
    }

    /** El plan del gimnasio, activo o no; vacío si no existe o es de otro gimnasio. */
    @Transactional(readOnly = true)
    public Optional<Plan> find(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM membership_plans WHERE id = :id AND organization_id = :org")
            .param("id", id).param("org", organizationId)
            .query(PlanService::map).optional();
    }

    /** Reemplaza los datos del plan; no cambia su estado. Vacío si no existe en el gimnasio. */
    @Transactional
    public Optional<Plan> update(long organizationId, long id, PlanData data) {
        try {
            return jdbc.sql("""
                    UPDATE membership_plans
                       SET name = :name, description = :description, price = :price,
                           duration_days = :days, benefits = :benefits
                     WHERE id = :id AND organization_id = :org
                    RETURNING """ + " " + COLUMNS)
                .param("id", id).param("org", organizationId)
                .param("name", data.name()).param("description", data.description())
                .param("price", data.price()).param("days", data.durationDays())
                .param("benefits", data.benefits())
                .query(PlanService::map).optional();
        } catch (DuplicateKeyException e) {
            throw new PlanNameTakenException();
        }
    }

    /** Activa o desactiva el plan (idempotente). Vacío si no existe en el gimnasio. */
    @Transactional
    public Optional<Plan> setActive(long organizationId, long id, boolean active) {
        return jdbc.sql("""
                UPDATE membership_plans SET is_active = :active
                 WHERE id = :id AND organization_id = :org
                RETURNING """ + " " + COLUMNS)
            .param("id", id).param("org", organizationId).param("active", active)
            .query(PlanService::map).optional();
    }

    private static Plan map(ResultSet rs, int row) throws SQLException {
        return new Plan(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
            rs.getBigDecimal("price"), rs.getInt("duration_days"), rs.getString("benefits"),
            rs.getBoolean("is_active"));
    }
}

package com.nexfit.administration;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catálogo de EPS: lectura para elegirla al registrar un cliente y mantenimiento del Master (US-18, NEX-30). */
@Service
class HealthInsuranceService {

    private final JdbcClient jdbc;

    HealthInsuranceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Provider(long id, String name, boolean active) { }

    /** Ya existe otra EPS con ese nombre, sin distinguir mayúsculas (409). */
    static class NameTakenException extends RuntimeException { }

    /** EPS por nombre; las inactivas solo se devuelven si {@code includeInactive} (el Master las administra). */
    @Transactional(readOnly = true)
    public List<Provider> list(boolean includeInactive) {
        return jdbc.sql("SELECT id, name, is_active FROM health_insurance_providers"
                + (includeInactive ? "" : " WHERE is_active") + " ORDER BY lower(name), id")
            .query((rs, n) -> new Provider(rs.getLong("id"), rs.getString("name"), rs.getBoolean("is_active")))
            .list();
    }

    @Transactional
    public Provider create(String name) {
        // Se serializa el alta para que dos nombres iguales enviados a la vez no pasen la verificación.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('health_insurance_providers'))").query().listOfRows();
        if (nameExists(name, null)) {
            throw new NameTakenException();
        }
        long id = jdbc.sql("INSERT INTO health_insurance_providers (name, is_active) VALUES (:name, true) RETURNING id")
            .param("name", name).query(Long.class).single();
        return new Provider(id, name, true);
    }

    @Transactional
    public Optional<Provider> rename(long id, String name) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('health_insurance_providers'))").query().listOfRows();
        if (find(id).isEmpty()) {
            return Optional.empty();
        }
        if (nameExists(name, id)) {
            throw new NameTakenException();
        }
        jdbc.sql("UPDATE health_insurance_providers SET name = :name WHERE id = :id")
            .param("name", name).param("id", id).update();
        return find(id);
    }

    /** Desactivar no toca a los clientes que ya la tienen: solo deja de ofrecerse al registrar. */
    @Transactional
    public Optional<Provider> setActive(long id, boolean active) {
        int rows = jdbc.sql("UPDATE health_insurance_providers SET is_active = :active WHERE id = :id")
            .param("active", active).param("id", id).update();
        return rows == 0 ? Optional.empty() : find(id);
    }

    private Optional<Provider> find(long id) {
        return jdbc.sql("SELECT id, name, is_active FROM health_insurance_providers WHERE id = :id")
            .param("id", id)
            .query((rs, n) -> new Provider(rs.getLong("id"), rs.getString("name"), rs.getBoolean("is_active")))
            .optional();
    }

    private boolean nameExists(String name, Long exceptId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM health_insurance_providers WHERE lower(name) = lower(:name)"
                + " AND (CAST(:except AS BIGINT) IS NULL OR id <> :except))")
            .param("name", name).param("except", exceptId).query(Boolean.class).single();
    }
}

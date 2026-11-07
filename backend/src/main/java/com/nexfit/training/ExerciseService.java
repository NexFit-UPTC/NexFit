package com.nexfit.training;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo de ejercicios (US-41, RN-39): los globales (organization_id nulo) los gestiona el Master y los ven todos
 * los gimnasios; los propios los crean el Administrador o el Entrenador y solo los ve su gimnasio. Un nombre no se
 * repite dentro del mismo ámbito (sin distinguir mayúsculas), pero uno propio puede llamarse como uno global.
 */
@Service
class ExerciseService {

    private static final String SELECT = """
        SELECT e.id, CASE WHEN e.organization_id IS NULL THEN 'global' ELSE 'own' END AS scope, e.name,
               e.muscle_group, e.description, e.image_url, e.is_active
          FROM exercises e
        """;

    private final JdbcClient jdbc;

    ExerciseService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Exercise(long id, String scope, String name, String muscleGroup, String description,
                           String imageUrl, boolean active) { }

    public record ExerciseData(String name, String muscleGroup, String description, String imageUrl) { }

    /** {@code orgId} nulo = Master (solo ve los globales). {@code scope}: all, global u own. */
    public record Filter(String q, String muscleGroup, String scope, Boolean active) { }

    /** Ya existe un ejercicio con ese nombre en el mismo ámbito (409). */
    static class NameTakenException extends RuntimeException {
        NameTakenException() {
            super(null, null, false, false);
        }
    }

    /** El ejercicio global solo lo modifica el Master (403). */
    static class GlobalReadOnlyException extends RuntimeException {
        GlobalReadOnlyException() {
            super(null, null, false, false);
        }
    }

    /** Crea un ejercicio global ({@code orgId} nulo) o propio. */
    @Transactional
    public Exercise create(Long orgId, ExerciseData data) {
        lockScope(orgId);
        if (nameTaken(orgId, data.name(), null)) {
            throw new NameTakenException();
        }
        try {
            long id = jdbc.sql("""
                    INSERT INTO exercises (organization_id, name, muscle_group, description, image_url, is_active)
                    VALUES (:org, :name, :muscle, :description, :image, true) RETURNING id""")
                .param("org", orgId, java.sql.Types.BIGINT).param("name", data.name())
                .param("muscle", data.muscleGroup(), java.sql.Types.VARCHAR)
                .param("description", data.description(), java.sql.Types.VARCHAR)
                .param("image", data.imageUrl(), java.sql.Types.VARCHAR)
                .query(Long.class).single();
            return find(orgId, id).orElseThrow();
        } catch (DataIntegrityViolationException e) {
            throw new NameTakenException();
        }
    }

    /**
     * Modifica un ejercicio del ámbito de quien lo pide: el Master, los globales; el Administrador, los propios. Un
     * global visto por un gimnasio es de solo lectura; uno propio de otro gimnasio no existe (404).
     */
    @Transactional
    public Optional<Exercise> update(Long orgId, long id, ExerciseData data) {
        Optional<Boolean> isGlobal = jdbc.sql("""
                SELECT organization_id IS NULL FROM exercises
                 WHERE id = :id AND (organization_id IS NULL OR organization_id = :org) FOR UPDATE""")
            .param("id", id).param("org", orgId, java.sql.Types.BIGINT).query(Boolean.class).optional();
        if (isGlobal.isEmpty() || (orgId == null && !isGlobal.get())) {
            return Optional.empty();
        }
        if (orgId != null && isGlobal.get()) {
            throw new GlobalReadOnlyException();
        }
        lockScope(orgId);
        if (nameTaken(orgId, data.name(), id)) {
            throw new NameTakenException();
        }
        try {
            jdbc.sql("""
                    UPDATE exercises SET name = :name, muscle_group = :muscle, description = :description,
                                         image_url = :image
                     WHERE id = :id""")
                .param("name", data.name()).param("muscle", data.muscleGroup(), java.sql.Types.VARCHAR)
                .param("description", data.description(), java.sql.Types.VARCHAR)
                .param("image", data.imageUrl(), java.sql.Types.VARCHAR).param("id", id).update();
        } catch (DataIntegrityViolationException e) {
            throw new NameTakenException();
        }
        return find(orgId, id);
    }

    /** Activa o desactiva un ejercicio global (solo el Master). */
    @Transactional
    public Optional<Exercise> setActive(long id, boolean active) {
        int rows = jdbc.sql("UPDATE exercises SET is_active = :active WHERE id = :id AND organization_id IS NULL")
            .param("active", active).param("id", id).update();
        return rows == 0 ? Optional.empty() : find(null, id);
    }

    /**
     * Lista los ejercicios visibles. Un gimnasio ve los globales y los suyos; el Master ({@code orgId} nulo), solo los
     * globales. {@code includeInactive} solo lo pide el Master.
     */
    @Transactional(readOnly = true)
    public List<Exercise> list(Long orgId, Filter filter, boolean includeInactive) {
        StringBuilder where = new StringBuilder(
            orgId == null ? " WHERE e.organization_id IS NULL"
                : " WHERE (e.organization_id IS NULL OR e.organization_id = :org)");
        List<Object[]> params = new ArrayList<>();
        if (!includeInactive) {
            where.append(" AND e.is_active");
        } else if (filter.active() != null) {
            where.append(" AND e.is_active = :active");
            params.add(new Object[] {"active", filter.active()});
        }
        if ("global".equals(filter.scope())) {
            where.append(" AND e.organization_id IS NULL");
        } else if ("own".equals(filter.scope())) {
            where.append(" AND e.organization_id IS NOT NULL");
        }
        if (filter.q() != null) {
            where.append(" AND lower(e.name) LIKE :q ESCAPE '\\'");
            params.add(new Object[] {"q", "%" + escapeLike(filter.q().toLowerCase()) + "%"});
        }
        if (filter.muscleGroup() != null) {
            where.append(" AND lower(e.muscle_group) = lower(:muscle)");
            params.add(new Object[] {"muscle", filter.muscleGroup()});
        }
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT + where + " ORDER BY lower(e.name), e.id");
        if (orgId != null) {
            spec = spec.param("org", orgId);
        }
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(ExerciseService::map).list();
    }

    /** Un ejercicio visible para quien lo pide (global o propio); el Master solo ve globales. */
    @Transactional(readOnly = true)
    public Optional<Exercise> find(Long orgId, long id) {
        return jdbc.sql(SELECT + " WHERE e.id = :id AND (e.organization_id IS NULL OR e.organization_id = :org)")
            .param("id", id).param("org", orgId, java.sql.Types.BIGINT).query(ExerciseService::map).optional()
            .filter(e -> orgId != null || e.scope().equals("global"));
    }

    private boolean nameTaken(Long orgId, String name, Long exceptId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM exercises
                                WHERE organization_id IS NOT DISTINCT FROM :org AND lower(name) = lower(:name)
                                  AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
            .param("org", orgId, java.sql.Types.BIGINT).param("name", name)
            .param("except", exceptId, java.sql.Types.BIGINT).query(Boolean.class).single();
    }

    /**
     * El índice único no cubre los globales (los nulos no se comparan entre sí), así que se serializa la creación y
     * la edición dentro de cada ámbito con un candado de transacción.
     */
    private void lockScope(Long orgId) {
        jdbc.sql("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) t")
            .param("key", orgId == null ? -1L : orgId).query(Long.class).single();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Exercise map(ResultSet rs, int row) throws SQLException {
        return new Exercise(rs.getLong("id"), rs.getString("scope"), rs.getString("name"),
            rs.getString("muscle_group"), rs.getString("description"), rs.getString("image_url"),
            rs.getBoolean("is_active"));
    }
}

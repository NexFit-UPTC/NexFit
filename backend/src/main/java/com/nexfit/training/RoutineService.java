package com.nexfit.training;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rutinas del entrenador (US-42, US-43, US-44, US-52). Una rutina es de un entrenador para un alumno suyo activo:
 * el entrenador la crea, edita y archiva; el cliente solo ve las suyas (también las archivadas). Una rutina archivada
 * no se edita. Todo se acota al gimnasio de la sesión.
 */
@Service
class RoutineService {

    private final JdbcClient jdbc;

    RoutineService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record RoutineExercise(long id, long exerciseId, String exerciseName, String muscleGroup, String imageUrl,
                                  int order, int series, int repetitions, int restSeconds) { }

    public record Summary(long id, long trainerMemberId, String trainerName, long clientMemberId, String clientName,
                          String name, String category, String level, Integer estimatedMinutes, Integer estimatedKcal,
                          OffsetDateTime createdAt, boolean active, long exerciseCount) { }

    public record Routine(long id, long trainerMemberId, String trainerName, long clientMemberId, String clientName,
                          String name, String category, String level, Integer estimatedMinutes, Integer estimatedKcal,
                          OffsetDateTime createdAt, boolean active, List<RoutineExercise> exercises) { }

    public record Item(long exerciseId, int order, int series, int repetitions, int restSeconds) { }

    public record Data(String name, String category, String level, Integer estimatedMinutes, Integer estimatedKcal,
                       List<Item> exercises) { }

    /** Quién consulta: el entrenador ve las que armó; el cliente, las suyas. */
    public record Viewer(long memberId, boolean trainer, boolean client) { }

    public record Filter(Long clientMemberId, String category, Boolean active) { }

    /** Un dato de la rutina no es válido (400, con el campo). */
    static class InvalidRoutine extends RuntimeException {
        private final String field;

        InvalidRoutine(String field, String message) {
            super(message, null, false, false);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /** La rutina está archivada y ya no se modifica (409). */
    static class ArchivedException extends RuntimeException {
        ArchivedException() {
            super(null, null, false, false);
        }
    }

    /** El cliente de la rutina ya no es alumno activo del entrenador (409). */
    static class NotStudentException extends RuntimeException {
        NotStudentException() {
            super(null, null, false, false);
        }
    }

    private static final String SELECT = """
        SELECT r.id, r.trainer_member_id, tu.first_name || ' ' || tu.last_name AS trainer_name, r.client_member_id,
               cu.first_name || ' ' || cu.last_name AS client_name, r.name, CAST(r.category AS text) AS category,
               CAST(r.level AS text) AS level, r.estimated_minutes, r.estimated_kcal, r.created_at, r.is_active,
               (SELECT count(*) FROM routine_exercises re WHERE re.routine_id = r.id) AS exercise_count
          FROM routines r
          JOIN organization_members tm ON tm.id = r.trainer_member_id
          JOIN users tu ON tu.id = tm.user_id
          JOIN organization_members cm ON cm.id = r.client_member_id
          JOIN users cu ON cu.id = cm.user_id
        """;

    private static final String VISIBLE = """
         WHERE r.organization_id = :org
           AND ((:trainer AND r.trainer_member_id = :me) OR (:client AND r.client_member_id = :me))
        """;

    @Transactional
    public Routine create(long orgId, long trainerId, long clientId, Data data) {
        if (!isActiveStudent(orgId, trainerId, clientId)) {
            throw new InvalidRoutine("clientMemberId", "El cliente no es alumno activo de este entrenador");
        }
        checkExercises(orgId, null, data.exercises());
        long id = jdbc.sql("""
                INSERT INTO routines (organization_id, trainer_member_id, client_member_id, name, category, level,
                                      estimated_minutes, estimated_kcal, created_at, is_active)
                VALUES (:org, :trainer, :client, :name, CAST(:category AS routine_category),
                        CAST(:level AS routine_level), :minutes, :kcal, now(), true)
                RETURNING id""")
            .param("org", orgId).param("trainer", trainerId).param("client", clientId).param("name", data.name())
            .param("category", data.category(), Types.VARCHAR).param("level", data.level(), Types.VARCHAR)
            .param("minutes", data.estimatedMinutes(), Types.INTEGER)
            .param("kcal", data.estimatedKcal(), Types.INTEGER).query(Long.class).single();
        insertItems(id, data.exercises());
        return load(orgId, id).orElseThrow();
    }

    /**
     * Reemplaza los datos y los ejercicios de una rutina propia. Vacío si no existe o no es del entrenador (404).
     */
    @Transactional
    public Optional<Routine> update(long orgId, long trainerId, long id, Data data) {
        Optional<long[]> row = jdbc.sql("""
                SELECT client_member_id, CASE WHEN is_active THEN 1 ELSE 0 END FROM routines
                 WHERE id = :id AND organization_id = :org AND trainer_member_id = :trainer FOR UPDATE""")
            .param("id", id).param("org", orgId).param("trainer", trainerId)
            .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        if (row.get()[1] == 0) {
            throw new ArchivedException();
        }
        if (!isActiveStudent(orgId, trainerId, row.get()[0])) {
            throw new NotStudentException();
        }
        checkExercises(orgId, id, data.exercises());
        jdbc.sql("""
                UPDATE routines SET name = :name, category = CAST(:category AS routine_category),
                       level = CAST(:level AS routine_level), estimated_minutes = :minutes, estimated_kcal = :kcal
                 WHERE id = :id""")
            .param("name", data.name()).param("category", data.category(), Types.VARCHAR)
            .param("level", data.level(), Types.VARCHAR).param("minutes", data.estimatedMinutes(), Types.INTEGER)
            .param("kcal", data.estimatedKcal(), Types.INTEGER).param("id", id).update();
        jdbc.sql("DELETE FROM routine_exercises WHERE routine_id = :id").param("id", id).update();
        insertItems(id, data.exercises());
        return load(orgId, id);
    }

    /** Archiva una rutina propia (idempotente). Vacío si no existe o no es del entrenador (404). */
    @Transactional
    public Optional<Routine> archive(long orgId, long trainerId, long id) {
        int n = jdbc.sql("""
                UPDATE routines SET is_active = false
                 WHERE id = :id AND organization_id = :org AND trainer_member_id = :trainer""")
            .param("id", id).param("org", orgId).param("trainer", trainerId).update();
        return n == 0 ? Optional.empty() : load(orgId, id);
    }

    @Transactional(readOnly = true)
    public List<Summary> list(long orgId, Viewer viewer, Filter filter) {
        return jdbc.sql(SELECT + VISIBLE + """
                   AND (CAST(:clientId AS bigint) IS NULL OR r.client_member_id = :clientId)
                   AND (CAST(:category AS text) IS NULL OR CAST(r.category AS text) = :category)
                   AND (CAST(:active AS boolean) IS NULL OR r.is_active = :active)
                 ORDER BY r.is_active DESC, r.created_at DESC, r.id DESC""")
            .param("org", orgId).param("trainer", viewer.trainer()).param("client", viewer.client())
            .param("me", viewer.memberId()).param("clientId", filter.clientMemberId(), Types.BIGINT)
            .param("category", filter.category(), Types.VARCHAR).param("active", filter.active(), Types.BOOLEAN)
            .query(RoutineService::summary).list();
    }

    @Transactional(readOnly = true)
    public Optional<Routine> find(long orgId, Viewer viewer, long id) {
        Optional<Routine> routine = load(orgId, id);
        return routine.filter(r -> (viewer.trainer() && r.trainerMemberId() == viewer.memberId())
            || (viewer.client() && r.clientMemberId() == viewer.memberId()));
    }

    @Transactional(readOnly = true)
    public Optional<Long> memberIdOf(long orgId, long userId) {
        return jdbc.sql("""
                SELECT id FROM organization_members
                 WHERE organization_id = :org AND user_id = :user AND is_active""")
            .param("org", orgId).param("user", userId).query(Long.class).optional();
    }

    // ---- internos -----------------------------------------------------------------------------

    private Optional<Routine> load(long orgId, long id) {
        Optional<Summary> head = jdbc.sql(SELECT + " WHERE r.organization_id = :org AND r.id = :id")
            .param("org", orgId).param("id", id).query(RoutineService::summary).optional();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        List<RoutineExercise> items = jdbc.sql("""
                SELECT re.id, re.exercise_id, e.name, e.muscle_group, e.image_url, re.exec_order, re.series,
                       re.repetitions, re.rest_seconds
                  FROM routine_exercises re JOIN exercises e ON e.id = re.exercise_id
                 WHERE re.routine_id = :id ORDER BY re.exec_order""")
            .param("id", id).query(RoutineService::item).list();
        Summary s = head.get();
        return Optional.of(new Routine(s.id(), s.trainerMemberId(), s.trainerName(), s.clientMemberId(),
            s.clientName(), s.name(), s.category(), s.level(), s.estimatedMinutes(), s.estimatedKcal(),
            s.createdAt(), s.active(), items));
    }

    private boolean isActiveStudent(long orgId, long trainerId, long clientId) {
        return jdbc.sql("""
                SELECT EXISTS (
                  SELECT 1 FROM trainer_clients tc
                    JOIN organization_members om ON om.id = tc.client_member_id
                    JOIN users u ON u.id = om.user_id
                   WHERE tc.trainer_member_id = :trainer AND tc.client_member_id = :client
                     AND tc.organization_id = :org AND tc.is_active AND om.is_active AND u.is_active)""")
            .param("trainer", trainerId).param("client", clientId).param("org", orgId)
            .query(Boolean.class).single();
    }

    /**
     * Cada ejercicio debe existir y estar activo para el gimnasio (global o propio). Al editar se admiten también los
     * que la rutina ya tenía, aunque después se hayan desactivado. El orden no se repite.
     */
    private void checkExercises(long orgId, Long routineId, List<Item> items) {
        Set<Integer> orders = new HashSet<>();
        Set<Long> wanted = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            if (!orders.add(items.get(i).order())) {
                throw new InvalidRoutine("exercises[" + i + "].order", "El orden de ejecución no puede repetirse");
            }
            wanted.add(items.get(i).exerciseId());
        }
        List<Long> allowed = jdbc.sql("""
                SELECT e.id FROM exercises e
                 WHERE e.id IN (:ids)
                   AND ((e.is_active AND (e.organization_id IS NULL OR e.organization_id = :org))
                        OR (CAST(:routine AS bigint) IS NOT NULL AND EXISTS (
                              SELECT 1 FROM routine_exercises re
                               WHERE re.routine_id = :routine AND re.exercise_id = e.id)
                            AND (e.organization_id IS NULL OR e.organization_id = :org)))""")
            .param("ids", new ArrayList<>(wanted)).param("org", orgId).param("routine", routineId, Types.BIGINT)
            .query(Long.class).list();
        for (int i = 0; i < items.size(); i++) {
            if (!allowed.contains(items.get(i).exerciseId())) {
                throw new InvalidRoutine("exercises[" + i + "].exerciseId",
                    "El ejercicio no existe en el catálogo o está inactivo");
            }
        }
    }

    private void insertItems(long routineId, List<Item> items) {
        for (Item item : items) {
            jdbc.sql("""
                    INSERT INTO routine_exercises (routine_id, exercise_id, exec_order, series, repetitions,
                                                   rest_seconds)
                    VALUES (:routine, :exercise, :order, :series, :reps, :rest)""")
                .param("routine", routineId).param("exercise", item.exerciseId()).param("order", item.order())
                .param("series", item.series()).param("reps", item.repetitions())
                .param("rest", item.restSeconds()).update();
        }
    }

    private static Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(rs.getLong("id"), rs.getLong("trainer_member_id"), rs.getString("trainer_name"),
            rs.getLong("client_member_id"), rs.getString("client_name"), rs.getString("name"),
            rs.getString("category"), rs.getString("level"), nullableInt(rs, "estimated_minutes"),
            nullableInt(rs, "estimated_kcal"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getBoolean("is_active"), rs.getLong("exercise_count"));
    }

    private static RoutineExercise item(ResultSet rs, int row) throws SQLException {
        return new RoutineExercise(rs.getLong("id"), rs.getLong("exercise_id"), rs.getString("name"),
            rs.getString("muscle_group"), rs.getString("image_url"), rs.getInt("exec_order"), rs.getInt("series"),
            rs.getInt("repetitions"), rs.getInt("rest_seconds"));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}

package com.nexfit.training;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entrenamientos realizados, racha, meta semanal y peso corporal del Cliente (US-53, US-54, US-55). Todo es del propio
 * cliente y del gimnasio de la sesión. Las fechas son las locales del establecimiento (RN-41). La racha no se guarda:
 * se calcula a partir de los entrenamientos (RN-42).
 */
@Service
class WorkoutService {

    private static final int MAX_LIST = 200;

    private final JdbcClient jdbc;

    WorkoutService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record WorkoutExercise(long routineExerciseId, long exerciseId, String exerciseName, int order,
                                  int series, int repetitions) { }

    public record Workout(long id, long routineId, String routineName, LocalDate performedOn, OffsetDateTime createdAt,
                          List<WorkoutExercise> exercises) { }

    public record WorkoutSummary(long id, long routineId, String routineName, LocalDate performedOn,
                                 long completedCount, long totalCount) { }

    /** Racha de días seguidos y avance de la semana actual (lunes a domingo) frente a la meta. */
    public record Progress(LocalDate today, int streak, Integer weeklyGoal, int weekCount, LocalDate weekStart,
                           LocalDate weekEnd, long totalWorkouts) { }

    public record WeightRecord(long id, LocalDate measuredOn, BigDecimal weightKg) { }

    public record WeightSaved(WeightRecord record, boolean replaced) { }

    /** Un dato no es válido (400, con el campo). */
    static class InvalidWorkout extends RuntimeException {
        private final String field;

        InvalidWorkout(String field, String message) {
            super(message, null, false, false);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /** La rutina está archivada: no admite entrenamientos nuevos (409). */
    static class ArchivedRoutineException extends RuntimeException {
        ArchivedRoutineException() {
            super(null, null, false, false);
        }
    }

    // ---- entrenamientos -----------------------------------------------------------------------

    /**
     * Registra un entrenamiento de una rutina activa del propio cliente. Vacío si la rutina no existe o no es suya (404).
     */
    @Transactional
    public Optional<Workout> log(long orgId, long memberId, long routineId, LocalDate performedOn, List<Long> done) {
        LocalDate today = today(orgId);
        LocalDate day = performedOn == null ? today : performedOn;
        if (day.isAfter(today)) {
            throw new InvalidWorkout("performedOn", "No se puede registrar un entrenamiento en una fecha futura");
        }
        Optional<Boolean> active = jdbc.sql("""
                SELECT is_active FROM routines
                 WHERE id = :id AND organization_id = :org AND client_member_id = :me""")
            .param("id", routineId).param("org", orgId).param("me", memberId).query(Boolean.class).optional();
        if (active.isEmpty()) {
            return Optional.empty();
        }
        if (!active.get()) {
            throw new ArchivedRoutineException();
        }
        Set<Long> ids = new LinkedHashSet<>(done);
        if (ids.isEmpty()) {
            throw new InvalidWorkout("completedExerciseIds", "Marca al menos un ejercicio completado");
        }
        long valid = jdbc.sql("SELECT count(*) FROM routine_exercises WHERE routine_id = :r AND id IN (:ids)")
            .param("r", routineId).param("ids", new ArrayList<>(ids)).query(Long.class).single();
        if (valid != ids.size()) {
            throw new InvalidWorkout("completedExerciseIds", "Hay ejercicios que no pertenecen a la rutina");
        }
        long id = jdbc.sql("""
                INSERT INTO workout_logs (organization_id, member_id, routine_id, performed_on, created_at)
                VALUES (:org, :me, :routine, :day, now()) RETURNING id""")
            .param("org", orgId).param("me", memberId).param("routine", routineId).param("day", day)
            .query(Long.class).single();
        for (Long exerciseId : ids) {
            jdbc.sql("INSERT INTO workout_log_items (workout_log_id, routine_exercise_id) VALUES (:log, :item)")
                .param("log", id).param("item", exerciseId).update();
        }
        return load(orgId, memberId, id);
    }

    @Transactional(readOnly = true)
    public List<WorkoutSummary> list(long orgId, long memberId, LocalDate from, LocalDate to, Long routineId) {
        return jdbc.sql("""
                SELECT w.id, w.routine_id, r.name, w.performed_on,
                       (SELECT count(*) FROM workout_log_items i WHERE i.workout_log_id = w.id) AS done,
                       (SELECT count(*) FROM routine_exercises re WHERE re.routine_id = w.routine_id) AS total
                  FROM workout_logs w JOIN routines r ON r.id = w.routine_id
                 WHERE w.organization_id = :org AND w.member_id = :me
                   AND (CAST(:from AS date) IS NULL OR w.performed_on >= :from)
                   AND (CAST(:to AS date) IS NULL OR w.performed_on <= :to)
                   AND (CAST(:routine AS bigint) IS NULL OR w.routine_id = :routine)
                 ORDER BY w.performed_on DESC, w.id DESC
                 LIMIT :limit""")
            .param("org", orgId).param("me", memberId).param("from", from, Types.DATE).param("to", to, Types.DATE)
            .param("routine", routineId, Types.BIGINT).param("limit", MAX_LIST)
            .query((rs, n) -> new WorkoutSummary(rs.getLong("id"), rs.getLong("routine_id"), rs.getString("name"),
                rs.getObject("performed_on", LocalDate.class), rs.getLong("done"), rs.getLong("total")))
            .list();
    }

    @Transactional(readOnly = true)
    public Optional<Workout> find(long orgId, long memberId, long id) {
        return load(orgId, memberId, id);
    }

    // ---- racha y meta -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Progress progress(long orgId, long memberId) {
        LocalDate today = today(orgId);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        List<LocalDate> days = jdbc.sql("""
                SELECT DISTINCT performed_on FROM workout_logs
                 WHERE organization_id = :org AND member_id = :me AND performed_on <= :today
                 ORDER BY performed_on DESC""")
            .param("org", orgId).param("me", memberId).param("today", today)
            .query((rs, n) -> rs.getObject("performed_on", LocalDate.class)).list();
        int week = (int) days.stream().filter(d -> !d.isBefore(weekStart)).count();
        Integer goal = jdbc.sql("SELECT weekly_goal_sessions FROM organization_members WHERE id = :me")
            .param("me", memberId).query((rs, n) -> {
                int g = rs.getInt(1);
                return rs.wasNull() ? null : g;
            }).optional().orElse(null);
        return new Progress(today, streak(days, today), goal, week, weekStart, weekEnd, days.size());
    }

    /** Días consecutivos que terminan hoy o ayer; si el último fue antes de ayer, la racha es 0. */
    static int streak(List<LocalDate> daysDescending, LocalDate today) {
        if (daysDescending.isEmpty()) {
            return 0;
        }
        LocalDate expected = daysDescending.get(0);
        if (expected.isBefore(today.minusDays(1))) {
            return 0;
        }
        int count = 0;
        for (LocalDate day : daysDescending) {
            if (!day.equals(expected)) {
                break;
            }
            count++;
            expected = expected.minusDays(1);
        }
        return count;
    }

    /** Fija (o borra, con nulo) la meta semanal del cliente. */
    @Transactional
    public Progress setGoal(long orgId, long memberId, Integer goal) {
        jdbc.sql("UPDATE organization_members SET weekly_goal_sessions = :goal WHERE id = :me AND organization_id = :org")
            .param("goal", goal, Types.SMALLINT).param("me", memberId).param("org", orgId).update();
        return progress(orgId, memberId);
    }

    // ---- peso corporal ------------------------------------------------------------------------

    /** Un solo peso por día: el segundo registro del mismo día reemplaza al anterior. */
    @Transactional
    public WeightSaved saveWeight(long orgId, long memberId, LocalDate measuredOn, BigDecimal kg) {
        LocalDate today = today(orgId);
        LocalDate day = measuredOn == null ? today : measuredOn;
        if (day.isAfter(today)) {
            throw new InvalidWorkout("measuredOn", "No se puede registrar el peso de una fecha futura");
        }
        return jdbc.sql("""
                INSERT INTO body_weight_records (organization_id, member_id, measured_on, weight_kg, created_at)
                VALUES (:org, :me, :day, :kg, now())
                ON CONFLICT (member_id, measured_on)
                DO UPDATE SET weight_kg = EXCLUDED.weight_kg, created_at = now()
                RETURNING id, measured_on, weight_kg, NOT (xmax = 0) AS replaced""")
            .param("org", orgId).param("me", memberId).param("day", day).param("kg", kg)
            .query((rs, n) -> new WeightSaved(new WeightRecord(rs.getLong("id"),
                rs.getObject("measured_on", LocalDate.class), rs.getBigDecimal("weight_kg")),
                rs.getBoolean("replaced")))
            .single();
    }

    /** Historial por fecha ascendente (la evolución). */
    @Transactional(readOnly = true)
    public List<WeightRecord> weights(long orgId, long memberId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT id, measured_on, weight_kg FROM body_weight_records
                 WHERE organization_id = :org AND member_id = :me
                   AND (CAST(:from AS date) IS NULL OR measured_on >= :from)
                   AND (CAST(:to AS date) IS NULL OR measured_on <= :to)
                 ORDER BY measured_on ASC LIMIT :limit""")
            .param("org", orgId).param("me", memberId).param("from", from, Types.DATE).param("to", to, Types.DATE)
            .param("limit", 1000)
            .query((rs, n) -> new WeightRecord(rs.getLong("id"), rs.getObject("measured_on", LocalDate.class),
                rs.getBigDecimal("weight_kg")))
            .list();
    }

    // ---- sesión y utilidades ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<Long> memberIdOf(long orgId, long userId) {
        return jdbc.sql("""
                SELECT id FROM organization_members
                 WHERE organization_id = :org AND user_id = :user AND is_active""")
            .param("org", orgId).param("user", userId).query(Long.class).optional();
    }

    private LocalDate today(long orgId) {
        String zone = jdbc.sql("SELECT timezone FROM organizations WHERE id = :org").param("org", orgId)
            .query(String.class).single();
        return LocalDate.now(ZoneId.of(zone));
    }

    private Optional<Workout> load(long orgId, long memberId, long id) {
        Optional<Workout> head = jdbc.sql("""
                SELECT w.id, w.routine_id, r.name, w.performed_on, w.created_at
                  FROM workout_logs w JOIN routines r ON r.id = w.routine_id
                 WHERE w.id = :id AND w.organization_id = :org AND w.member_id = :me""")
            .param("id", id).param("org", orgId).param("me", memberId)
            .query((rs, n) -> new Workout(rs.getLong("id"), rs.getLong("routine_id"), rs.getString("name"),
                rs.getObject("performed_on", LocalDate.class), rs.getObject("created_at", OffsetDateTime.class),
                List.of()))
            .optional();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        List<WorkoutExercise> items = jdbc.sql("""
                SELECT re.id, re.exercise_id, e.name, re.exec_order, re.series, re.repetitions
                  FROM workout_log_items i
                  JOIN routine_exercises re ON re.id = i.routine_exercise_id
                  JOIN exercises e ON e.id = re.exercise_id
                 WHERE i.workout_log_id = :id ORDER BY re.exec_order""")
            .param("id", id)
            .query((rs, n) -> new WorkoutExercise(rs.getLong("id"), rs.getLong("exercise_id"), rs.getString("name"),
                rs.getInt("exec_order"), rs.getInt("series"), rs.getInt("repetitions")))
            .list();
        Workout w = head.get();
        return Optional.of(new Workout(w.id(), w.routineId(), w.routineName(), w.performedOn(), w.createdAt(), items));
    }
}

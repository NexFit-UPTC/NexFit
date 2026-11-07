package com.nexfit.training;

import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import com.nexfit.training.WorkoutService.ArchivedRoutineException;
import com.nexfit.training.WorkoutService.InvalidWorkout;
import com.nexfit.training.WorkoutService.Progress;
import com.nexfit.training.WorkoutService.WeightRecord;
import com.nexfit.training.WorkoutService.WeightSaved;
import com.nexfit.training.WorkoutService.Workout;
import com.nexfit.training.WorkoutService.WorkoutSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrenamientos realizados, racha, meta semanal y peso corporal (US-53, US-54, US-55). Solo el Cliente, y solo lo
 * suyo: la matriz da ENTRENAMIENTOS únicamente al Cliente, así que Staff, Administrador, Master y Entrenador reciben
 * 403 (el peso es un dato de salud, RNF-08).
 */
@RestController
class WorkoutController {

    private final WorkoutService workouts;

    WorkoutController(WorkoutService workouts) {
        this.workouts = workouts;
    }

    /** Cuerpo: {@code routineId}, {@code completedExerciseIds} y, opcional, {@code performedOn} (hoy por defecto). */
    @RequiresAccess(funcion = Funcion.ENTRENAMIENTOS, accion = Accion.CREAR)
    @PostMapping("/workouts")
    ResponseEntity<Workout> log(@RequestBody Map<String, Object> body) {
        long orgId = ActiveOrganization.id();
        long routineId = requiredLong(body, "routineId", "Indica la rutina");
        LocalDate day = optionalDate(body, "performedOn");
        List<Long> done = idList(body, "completedExerciseIds");
        return workouts.log(orgId, memberId(orgId), routineId, day, done)
            .map(w -> ResponseEntity.status(HttpStatus.CREATED).body(w))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.ENTRENAMIENTOS, accion = Accion.CONSULTAR)
    @GetMapping("/workouts")
    List<WorkoutSummary> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                              @RequestParam(required = false) Long routineId) {
        long orgId = ActiveOrganization.id();
        return workouts.list(orgId, memberId(orgId), queryDate("from", from), queryDate("to", to), routineId);
    }

    @RequiresAccess(funcion = Funcion.ENTRENAMIENTOS, accion = Accion.CONSULTAR)
    @GetMapping("/workouts/{id}")
    ResponseEntity<Workout> detail(@PathVariable long id) {
        long orgId = ActiveOrganization.id();
        return workouts.find(orgId, memberId(orgId), id).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Racha, meta y avance de la semana actual. */
    @RequiresAccess(funcion = Funcion.ENTRENAMIENTOS, accion = Accion.CONSULTAR)
    @GetMapping("/my-progress")
    Progress progress() {
        long orgId = ActiveOrganization.id();
        return workouts.progress(orgId, memberId(orgId));
    }

    /** Cuerpo {@code {"weeklyGoal": 1..7}}; {@code null} borra la meta. */
    @RequiresAccess(funcion = Funcion.ENTRENAMIENTOS, accion = Accion.MODIFICAR)
    @PutMapping("/my-goal")
    Progress setGoal(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("weeklyGoal")) {
            throw new Invalid("weeklyGoal", "Indica la meta semanal (1 a 7) o null para quitarla");
        }
        Object value = body.get("weeklyGoal");
        Integer goal = null;
        if (value != null) {
            if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 1
                || n.intValue() > 7) {
                throw new Invalid("weeklyGoal", "La meta semanal va de 1 a 7 entrenamientos");
            }
            goal = n.intValue();
        }
        long orgId = ActiveOrganization.id();
        return workouts.setGoal(orgId, memberId(orgId), goal);
    }

    /** Cuerpo {@code {"weightKg": 20..500, "measuredOn"?}}. 201 si es nuevo; 200 si reemplaza el del mismo día. */
    @RequiresAccess(funcion = Funcion.ENTRENAMIENTOS, accion = Accion.CREAR)
    @PostMapping("/body-weight")
    ResponseEntity<WeightRecord> saveWeight(@RequestBody Map<String, Object> body) {
        BigDecimal kg = weight(body.get("weightKg"));
        LocalDate day = optionalDate(body, "measuredOn");
        long orgId = ActiveOrganization.id();
        WeightSaved saved = workouts.saveWeight(orgId, memberId(orgId), day, kg);
        return ResponseEntity.status(saved.replaced() ? HttpStatus.OK : HttpStatus.CREATED).body(saved.record());
    }

    @RequiresAccess(funcion = Funcion.ENTRENAMIENTOS, accion = Accion.CONSULTAR)
    @GetMapping("/body-weight")
    List<WeightRecord> weights(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        long orgId = ActiveOrganization.id();
        return workouts.weights(orgId, memberId(orgId), queryDate("from", from), queryDate("to", to));
    }

    // ---- sesión -----------------------------------------------------------------------------

    private long memberId(long orgId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            throw new AccessDeniedException("Sin sesión");
        }
        return workouts.memberIdOf(orgId, userId).orElseThrow(() -> new AccessDeniedException("Sin afiliación"));
    }

    // ---- validación -------------------------------------------------------------------------

    private static long requiredLong(Map<String, Object> body, String field, String message) {
        if (body.get(field) instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.longValue();
        }
        throw new Invalid(field, message);
    }

    private static List<Long> idList(Map<String, Object> body, String field) {
        if (!(body.get(field) instanceof List<?> raw) || raw.isEmpty()) {
            throw new Invalid(field, "Marca al menos un ejercicio completado");
        }
        List<Long> ids = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
                throw new Invalid(field, "Los ejercicios se indican por su identificador");
            }
            ids.add(n.longValue());
        }
        return ids;
    }

    private static LocalDate optionalDate(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            try {
                return LocalDate.parse(s);
            } catch (DateTimeParseException e) {
                // cae al error común
            }
        }
        throw new Invalid(field, "La fecha debe tener el formato AAAA-MM-DD");
    }

    private static LocalDate queryDate(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new Invalid(field, "La fecha debe tener el formato AAAA-MM-DD");
        }
    }

    private static BigDecimal weight(Object value) {
        if (value instanceof Number n) {
            BigDecimal kg = new BigDecimal(n.toString());
            if (kg.compareTo(BigDecimal.valueOf(20)) >= 0 && kg.compareTo(BigDecimal.valueOf(500)) <= 0
                && kg.stripTrailingZeros().scale() <= 2) {
                return kg;
            }
        }
        throw new Invalid("weightKg", "El peso va de 20 a 500 kg, con hasta dos decimales");
    }

    // ---- errores ----------------------------------------------------------------------------

    static class Invalid extends RuntimeException {
        private final String field;

        Invalid(String field, String message) {
            super(message, null, false, false);
            this.field = field;
        }
    }

    @ExceptionHandler(Invalid.class)
    ResponseEntity<Map<String, String>> onInvalid(Invalid e) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", e.field, e.getMessage()));
    }

    @ExceptionHandler(InvalidWorkout.class)
    ResponseEntity<Map<String, String>> onInvalidWorkout(InvalidWorkout e) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", e.field(), e.getMessage()));
    }

    @ExceptionHandler(ArchivedRoutineException.class)
    ResponseEntity<Map<String, String>> onArchived() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("ROUTINE_ARCHIVED", "routineId", "La rutina está archivada: ya no admite entrenamientos"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

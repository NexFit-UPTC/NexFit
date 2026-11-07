package com.nexfit.training;

import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.RequiresAccess;
import com.nexfit.training.ExerciseService.Exercise;
import com.nexfit.training.ExerciseService.ExerciseData;
import com.nexfit.training.ExerciseService.Filter;
import com.nexfit.training.ExerciseService.GlobalReadOnlyException;
import com.nexfit.training.ExerciseService.NameTakenException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo de ejercicios (US-41): /api/exercises. El Master gestiona el catálogo global (no tiene gimnasio activo);
 * el Administrador y el Entrenador crean ejercicios propios de su gimnasio, y el Administrador los modifica; Staff y
 * Cliente solo leen. Los gimnasios ven los globales activos y los suyos.
 */
@RestController
@RequestMapping("/exercises")
class ExerciseController {

    private static final int NAME_MAX = 100;
    private static final int MUSCLE_MAX = 100;
    private static final int DESCRIPTION_MAX = 2000;
    private static final int IMAGE_MAX = 500;
    private static final Set<String> SCOPES = Set.of("all", "global", "own");

    private final ExerciseService exercises;

    ExerciseController(ExerciseService exercises) {
        this.exercises = exercises;
    }

    /** Cuerpo: {@code name}, {@code muscleGroup?}, {@code description?}, {@code imageUrl?}. */
    @RequiresAccess(funcion = Funcion.CATALOGO_EJERCICIOS, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<Exercise> create(@RequestBody Map<String, Object> body) {
        ExerciseData data = parse(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(exercises.create(orgOrNull(), data));
    }

    /** Filtros: {@code q} (parte del nombre), {@code muscleGroup}, {@code scope} (all, global, own) y, solo para el Master, {@code active}. */
    @RequiresAccess(funcion = Funcion.CATALOGO_EJERCICIOS, accion = Accion.CONSULTAR)
    @GetMapping
    List<Exercise> list(@RequestParam(required = false) String q, @RequestParam(required = false) String muscleGroup,
                        @RequestParam(defaultValue = "all") String scope,
                        @RequestParam(required = false) Boolean active) {
        if (!SCOPES.contains(scope)) {
            throw new Invalid("scope", "El ámbito debe ser all, global u own");
        }
        String query = q == null || q.isBlank() ? null : q.trim();
        String muscle = muscleGroup == null || muscleGroup.isBlank() ? null : muscleGroup.trim();
        return exercises.list(orgOrNull(), new Filter(query, muscle, scope, active), isMaster());
    }

    @RequiresAccess(funcion = Funcion.CATALOGO_EJERCICIOS, accion = Accion.CONSULTAR)
    @GetMapping("/{id}")
    ResponseEntity<Exercise> get(@PathVariable long id) {
        return exercises.find(orgOrNull(), id).filter(e -> e.active() || isMaster()).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.CATALOGO_EJERCICIOS, accion = Accion.MODIFICAR)
    @PutMapping("/{id}")
    ResponseEntity<Exercise> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ExerciseData data = parse(body);
        return exercises.update(orgOrNull(), id, data).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.CATALOGO_EJERCICIOS, accion = Accion.ACTIVAR)
    @PatchMapping("/{id}/active")
    ResponseEntity<Exercise> setActive(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (!(body.get("active") instanceof Boolean active)) {
            throw new Invalid("active", "Indica si el ejercicio queda activo (true) o inactivo (false)");
        }
        return exercises.setActive(id, active).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- sesión -----------------------------------------------------------------------------

    /** El Master es el único con ACTIVAR sobre el catálogo; no opera dentro de un gimnasio. */
    private static boolean isMaster() {
        return Permisos.permite(Funcion.CATALOGO_EJERCICIOS, Accion.ACTIVAR,
            SecurityContextHolder.getContext().getAuthentication().getAuthorities());
    }

    /** Gimnasio activo de la sesión, o nulo para el Master (trabaja con el catálogo global). */
    private static Long orgOrNull() {
        return isMaster() ? null : ActiveOrganization.id();
    }

    // ---- validación -------------------------------------------------------------------------

    private static ExerciseData parse(Map<String, Object> body) {
        String name = text(body.get("name"));
        if (name == null || name.isEmpty()) {
            throw new Invalid("name", "El nombre es obligatorio");
        }
        if (name.length() > NAME_MAX) {
            throw new Invalid("name", "El nombre no puede superar " + NAME_MAX + " caracteres");
        }
        return new ExerciseData(name, optional(body, "muscleGroup", MUSCLE_MAX),
            optional(body, "description", DESCRIPTION_MAX), image(body.get("imageUrl")));
    }

    private static String image(Object value) {
        if (value == null) {
            return null;
        }
        String url = text(value);
        if (url == null) {
            throw new Invalid("imageUrl", "La imagen debe ser una dirección de texto");
        }
        if (url.isEmpty()) {
            return null;
        }
        if (url.length() > IMAGE_MAX || !(url.startsWith("https://") || url.startsWith("/")) || url.startsWith("//")) {
            throw new Invalid("imageUrl", "La imagen debe empezar por https:// o por / y tener máximo " + IMAGE_MAX
                + " caracteres");
        }
        return url;
    }

    private static String optional(Map<String, Object> body, String field, int max) {
        Object raw = body.get(field);
        if (raw == null) {
            return null;
        }
        String value = text(raw);
        if (value == null) {
            throw new Invalid(field, "El campo " + field + " debe ser texto");
        }
        if (value.length() > max) {
            throw new Invalid(field, "El campo " + field + " no puede superar " + max + " caracteres");
        }
        return value.isEmpty() ? null : value;
    }

    private static String text(Object value) {
        return value instanceof String s ? s.trim() : null;
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

    @ExceptionHandler(NameTakenException.class)
    ResponseEntity<Map<String, String>> onNameTaken() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("EXERCISE_NAME_TAKEN", "name", "Ya existe un ejercicio con ese nombre en este catálogo"));
    }

    @ExceptionHandler(GlobalReadOnlyException.class)
    ResponseEntity<Map<String, String>> onGlobalReadOnly() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(error("EXERCISE_GLOBAL_READ_ONLY", "id", "Los ejercicios globales solo los modifica el Master"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

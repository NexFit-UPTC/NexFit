package com.nexfit.training;

import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import com.nexfit.shared.Rol;
import com.nexfit.training.RoutineService.ArchivedException;
import com.nexfit.training.RoutineService.Data;
import com.nexfit.training.RoutineService.Filter;
import com.nexfit.training.RoutineService.InvalidRoutine;
import com.nexfit.training.RoutineService.Item;
import com.nexfit.training.RoutineService.NotStudentException;
import com.nexfit.training.RoutineService.Routine;
import com.nexfit.training.RoutineService.Summary;
import com.nexfit.training.RoutineService.Viewer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rutinas (US-42, US-43, US-44, US-52). El Entrenador crea, modifica y archiva las suyas; el Cliente solo consulta las
 * suyas. Staff, Administrador y Master no tienen acceso (la matriz solo da RUTINAS al Entrenador y al Cliente).
 */
@RestController
class RoutineController {

    static final Set<String> CATEGORIES = Set.of("strength", "cardio", "mobility");
    static final Set<String> LEVELS = Set.of("beginner", "intermediate", "advanced");
    static final int MAX_EXERCISES = 50;

    private final RoutineService routines;

    RoutineController(RoutineService routines) {
        this.routines = routines;
    }

    @RequiresAccess(funcion = Funcion.RUTINAS, accion = Accion.CREAR)
    @PostMapping("/routines")
    ResponseEntity<Routine> create(@RequestBody Map<String, Object> body) {
        long orgId = ActiveOrganization.id();
        long client = requiredLong(body, "clientMemberId", "Indica el alumno");
        Data data = parse(body);
        Routine created = routines.create(orgId, actorMemberId(orgId), client, data);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @RequiresAccess(funcion = Funcion.RUTINAS, accion = Accion.MODIFICAR)
    @PutMapping("/routines/{id}")
    ResponseEntity<Routine> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        long orgId = ActiveOrganization.id();
        Data data = parse(body);
        return routines.update(orgId, actorMemberId(orgId), id, data).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.RUTINAS, accion = Accion.MODIFICAR)
    @PatchMapping("/routines/{id}/archive")
    ResponseEntity<Routine> archive(@PathVariable long id) {
        long orgId = ActiveOrganization.id();
        return routines.archive(orgId, actorMemberId(orgId), id).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Filtros: {@code clientMemberId} (solo el Entrenador), {@code category}, {@code active}. */
    @RequiresAccess(funcion = Funcion.RUTINAS, accion = Accion.CONSULTAR)
    @GetMapping("/routines")
    List<Summary> list(@RequestParam(required = false) Long clientMemberId,
                       @RequestParam(required = false) String category,
                       @RequestParam(required = false) Boolean active) {
        if (category != null && !CATEGORIES.contains(category)) {
            throw new Invalid("category", "La categoría debe ser strength, cardio o mobility");
        }
        long orgId = ActiveOrganization.id();
        return routines.list(orgId, viewer(orgId), new Filter(clientMemberId, category, active));
    }

    @RequiresAccess(funcion = Funcion.RUTINAS, accion = Accion.CONSULTAR)
    @GetMapping("/routines/{id}")
    ResponseEntity<Routine> detail(@PathVariable long id) {
        long orgId = ActiveOrganization.id();
        return routines.find(orgId, viewer(orgId), id).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- sesión -----------------------------------------------------------------------------

    private static Authentication auth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long)) {
            throw new AccessDeniedException("Sin sesión");
        }
        return auth;
    }

    private long actorMemberId(long orgId) {
        long userId = (Long) auth().getPrincipal();
        return routines.memberIdOf(orgId, userId).orElseThrow(() -> new AccessDeniedException("Sin afiliación"));
    }

    private Viewer viewer(long orgId) {
        Authentication auth = auth();
        boolean trainer = false;
        boolean client = false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            Rol rol = Rol.deAutoridad(a.getAuthority()).orElse(null);
            trainer |= rol == Rol.TRAINER;
            client |= rol == Rol.CLIENT;
        }
        return new Viewer(actorMemberId(orgId), trainer, client);
    }

    // ---- validación -------------------------------------------------------------------------

    private static Data parse(Map<String, Object> body) {
        String name = body.get("name") instanceof String s ? s.trim() : "";
        if (name.isEmpty() || name.length() > 150) {
            throw new Invalid("name", "El nombre es obligatorio y tiene hasta 150 caracteres");
        }
        String category = optionalEnum(body, "category", CATEGORIES, "La categoría debe ser strength, cardio o mobility");
        String level = optionalEnum(body, "level", LEVELS, "El nivel debe ser beginner, intermediate o advanced");
        Integer minutes = optionalInt(body, "estimatedMinutes", 1, 1440, "Los minutos estimados van de 1 a 1440");
        Integer kcal = optionalInt(body, "estimatedKcal", 1, 20000, "Las kcal estimadas van de 1 a 20000");
        if (!(body.get("exercises") instanceof List<?> raw) || raw.isEmpty()) {
            throw new Invalid("exercises", "La rutina necesita al menos un ejercicio");
        }
        if (raw.size() > MAX_EXERCISES) {
            throw new Invalid("exercises", "Una rutina tiene hasta " + MAX_EXERCISES + " ejercicios");
        }
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Map<?, ?> m)) {
                throw new Invalid("exercises[" + i + "]", "Ejercicio inválido");
            }
            String at = "exercises[" + i + "].";
            long exerciseId = requiredLong(m, at + "exerciseId", "exerciseId", "Indica el ejercicio");
            int order = m.get("order") == null ? i + 1
                : requiredInt(m, at + "order", "order", 1, 1000, "El orden va de 1 a 1000");
            int series = requiredInt(m, at + "series", "series", 1, 50, "Las series van de 1 a 50");
            int reps = requiredInt(m, at + "repetitions", "repetitions", 1, 999, "Las repeticiones van de 1 a 999");
            int rest = requiredInt(m, at + "restSeconds", "restSeconds", 0, 3600,
                "El descanso va de 0 a 3600 segundos");
            items.add(new Item(exerciseId, order, series, reps, rest));
        }
        return new Data(name, category, level, minutes, kcal, items);
    }

    private static String optionalEnum(Map<String, Object> body, String field, Set<String> allowed, String message) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String s && allowed.contains(s)) {
            return s;
        }
        throw new Invalid(field, message);
    }

    private static Integer optionalInt(Map<String, Object> body, String field, int min, int max, String message) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        return requiredInt(body, field, field, min, max, message);
    }

    private static long requiredLong(Map<?, ?> body, String field, String message) {
        return requiredLong(body, field, field, message);
    }

    private static long requiredLong(Map<?, ?> body, String reported, String key, String message) {
        if (body.get(key) instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.longValue();
        }
        throw new Invalid(reported, message);
    }

    private static int requiredInt(Map<?, ?> body, String reported, String key, int min, int max, String message) {
        if (body.get(key) instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
            && n.longValue() >= min && n.longValue() <= max) {
            return n.intValue();
        }
        throw new Invalid(reported, message);
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

    @ExceptionHandler(InvalidRoutine.class)
    ResponseEntity<Map<String, String>> onInvalidRoutine(InvalidRoutine e) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", e.field(), e.getMessage()));
    }

    @ExceptionHandler(ArchivedException.class)
    ResponseEntity<Map<String, String>> onArchived() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("ROUTINE_ARCHIVED", "id", "La rutina está archivada y ya no se puede modificar"));
    }

    @ExceptionHandler(NotStudentException.class)
    ResponseEntity<Map<String, String>> onNotStudent() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("NOT_ASSIGNED", "clientMemberId", "El cliente ya no es alumno activo de este entrenador"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

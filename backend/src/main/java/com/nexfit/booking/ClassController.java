package com.nexfit.booking;

import com.nexfit.booking.ClassService.CapacityBelowBookingsException;
import com.nexfit.booking.ClassService.ClassCancelledException;
import com.nexfit.booking.ClassService.ClassData;
import com.nexfit.booking.ClassService.ClassFilter;
import com.nexfit.booking.ClassService.ClassHasBookingsException;
import com.nexfit.booking.ClassService.ClassPage;
import com.nexfit.booking.ClassService.GymClass;
import com.nexfit.booking.ClassService.InvalidClassException;
import com.nexfit.booking.ClassService.TrainerOverlapException;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.RequiresAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * Clases del gimnasio (US-29, NEX-40): /api/classes. El Administrador las programa, consulta y modifica;
 * Staff, Entrenador y Cliente solo consultan y solo ven las programadas (no las canceladas). El gimnasio
 * sale siempre de la sesión (ActiveOrganization). Cancelar una clase es US-30 (NEX-68).
 */
@RestController
@RequestMapping("/classes")
class ClassController {

    private static final int NAME_MAX = 150;
    private static final int DESCRIPTION_MAX = 2000;
    private static final int DURATION_MAX = 24 * 60;
    private static final int CAPACITY_MAX = 10_000;

    private final ClassService classes;

    ClassController(ClassService classes) {
        this.classes = classes;
    }

    @RequiresAccess(funcion = Funcion.CLASES, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<GymClass> create(@RequestBody Map<String, Object> body) {
        ClassData data = parse(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(classes.create(ActiveOrganization.id(), data));
    }

    /**
     * Página de clases por fecha de inicio. Filtros opcionales: {@code branchId}, {@code trainerMemberId},
     * {@code from} (incluye), {@code to} (excluye; ambos con fecha y hora y zona, p. ej.
     * 2026-11-10T00:00:00-05:00) y {@code status} (solo lo respeta quien administra clases).
     */
    @RequiresAccess(funcion = Funcion.CLASES, accion = Accion.CONSULTAR)
    @GetMapping
    ClassPage list(@RequestParam(required = false) Long branchId,
                   @RequestParam(required = false) Long trainerMemberId,
                   @RequestParam(required = false) String from,
                   @RequestParam(required = false) String to,
                   @RequestParam(required = false) String status,
                   @RequestParam(defaultValue = "0") int page,
                   @RequestParam(defaultValue = "20") int size) {
        if (status != null && !status.equals("scheduled") && !status.equals("cancelled")) {
            throw new Invalid("status", "El estado debe ser scheduled o cancelled");
        }
        ClassFilter filter = new ClassFilter(branchId, trainerMemberId, instant("from", from), instant("to", to), status);
        return classes.list(ActiveOrganization.id(), filter, !canManage(), page, size);
    }

    @RequiresAccess(funcion = Funcion.CLASES, accion = Accion.CONSULTAR)
    @GetMapping("/{id}")
    ResponseEntity<GymClass> get(@PathVariable long id) {
        return classes.find(ActiveOrganization.id(), id)
            .filter(c -> c.status().equals("scheduled") || canManage())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.CLASES, accion = Accion.MODIFICAR)
    @PutMapping("/{id}")
    ResponseEntity<GymClass> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ClassData data = parse(body);
        return classes.update(ActiveOrganization.id(), id, data)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Cancela la clase y sus reservas confirmadas. Cuerpo opcional {@code {"reason": "…"}}. */
    @RequiresAccess(funcion = Funcion.CLASES, accion = Accion.CANCELAR)
    @PatchMapping("/{id}/cancel")
    ResponseEntity<GymClass> cancel(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        String reason = null;
        if (body != null && body.get("reason") != null) {
            reason = text(body.get("reason"));
            if (reason == null) {
                throw new Invalid("reason", "El motivo debe ser texto");
            }
            if (reason.length() > DESCRIPTION_MAX) {
                throw new Invalid("reason", "El motivo no puede superar " + DESCRIPTION_MAX + " caracteres");
            }
            reason = reason.isEmpty() ? null : reason;
        }
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        long actor = principal instanceof Long userId ? userId : 0L;
        return classes.cancel(ActiveOrganization.id(), actor, id, reason, request.getRemoteAddr())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** ¿Quien consulta puede administrar clases (ve también las canceladas)? */
    private static boolean canManage() {
        return Permisos.permite(Funcion.CLASES, Accion.MODIFICAR,
            SecurityContextHolder.getContext().getAuthentication().getAuthorities());
    }

    // ---- validación -------------------------------------------------------------------------

    private static ClassData parse(Map<String, Object> body) {
        String name = text(body.get("name"));
        if (name == null || name.isEmpty()) {
            throw new Invalid("name", "El nombre es obligatorio");
        }
        if (name.length() > NAME_MAX) {
            throw new Invalid("name", "El nombre no puede superar " + NAME_MAX + " caracteres");
        }
        String description = null;
        if (body.get("description") != null) {
            description = text(body.get("description"));
            if (description == null) {
                throw new Invalid("description", "La descripción debe ser texto");
            }
            if (description.length() > DESCRIPTION_MAX) {
                throw new Invalid("description", "La descripción no puede superar " + DESCRIPTION_MAX + " caracteres");
            }
            description = description.isEmpty() ? null : description;
        }
        long branchId = positiveId(body, "branchId", "La sede es obligatoria");
        Long trainer = body.get("trainerMemberId") == null ? null
            : positiveId(body, "trainerMemberId", "El entrenador debe ser un identificador válido");
        OffsetDateTime startsAt = instant("startsAt", body.get("startsAt") instanceof String s ? s : null);
        if (startsAt == null) {
            throw new Invalid("startsAt", "La fecha y hora de inicio son obligatorias");
        }
        int duration = integer(body, "durationMinutes", "La duración", 1, DURATION_MAX);
        int capacity = integer(body, "capacity", "La capacidad", 1, CAPACITY_MAX);
        return new ClassData(name, description, branchId, trainer, startsAt, duration, capacity);
    }

    private static long positiveId(Map<String, Object> body, String field, String message) {
        if (body.get(field) instanceof Number n && n.longValue() > 0 && n.doubleValue() == n.longValue()) {
            return n.longValue();
        }
        throw new Invalid(field, message);
    }

    private static int integer(Map<String, Object> body, String field, String label, int min, int max) {
        if (!(body.get(field) instanceof Number n) || n.doubleValue() != n.longValue()) {
            throw new Invalid(field, label + " es obligatoria y debe ser un número entero");
        }
        long value = n.longValue();
        if (value < min) {
            throw new Invalid(field, label + " debe ser mayor que cero");
        }
        if (value > max) {
            throw new Invalid(field, label + " no puede superar " + max);
        }
        return (int) value;
    }

    /** Fecha y hora con zona (ISO-8601); null si no se envió. */
    private static OffsetDateTime instant(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new Invalid(field, "Usa fecha y hora con zona, p. ej. 2026-11-10T18:00:00-05:00");
        }
    }

    /** Valor de texto recortado; null si no es una cadena. */
    private static String text(Object value) {
        return value instanceof String s ? s.trim() : null;
    }

    // ---- errores ----------------------------------------------------------------------------

    /** Dato inválido de la solicitud; se responde 400 con el campo (mismo formato que el resto de la API). */
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

    @ExceptionHandler(InvalidClassException.class)
    ResponseEntity<Map<String, String>> onInvalidClass(InvalidClassException e) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", e.field(), e.getMessage()));
    }

    @ExceptionHandler(TrainerOverlapException.class)
    ResponseEntity<Map<String, String>> onOverlap() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("TRAINER_OVERLAP", "trainerMemberId",
            "El entrenador ya tiene otra clase programada que se cruza con ese horario"));
    }

    @ExceptionHandler(CapacityBelowBookingsException.class)
    ResponseEntity<Map<String, String>> onCapacity(CapacityBelowBookingsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("CAPACITY_BELOW_BOOKINGS", "capacity",
            "La capacidad no puede ser menor que las " + e.confirmed() + " reservas confirmadas"));
    }

    @ExceptionHandler(ClassHasBookingsException.class)
    ResponseEntity<Map<String, String>> onHasBookings() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("CLASS_HAS_BOOKINGS", "startsAt",
            "La clase tiene reservas confirmadas: no se puede cambiar su fecha ni su hora"));
    }

    @ExceptionHandler(ClassCancelledException.class)
    ResponseEntity<Map<String, String>> onCancelled() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("CLASS_CANCELLED", "status",
            "La clase está cancelada y no admite cambios"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

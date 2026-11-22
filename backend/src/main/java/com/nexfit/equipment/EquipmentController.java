package com.nexfit.equipment;

import com.nexfit.equipment.EquipmentService.Equipment;
import com.nexfit.equipment.EquipmentService.EquipmentData;
import com.nexfit.equipment.EquipmentService.Filter;
import com.nexfit.equipment.EquipmentService.InvalidBranchException;
import com.nexfit.equipment.EquipmentService.RetiredException;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * Equipo de las sedes (US-12): /api/equipment. El Administrador lo registra y modifica; el Staff solo lo consulta.
 * El gimnasio sale siempre de la sesión (ActiveOrganization).
 */
@RestController
@RequestMapping("/equipment")
class EquipmentController {

    static final Set<String> STATUSES = Set.of("available", "maintenance", "out_of_service", "retired");
    private static final int NAME_MAX = 150;
    private static final int CATEGORY_MAX = 100;
    private static final int IMAGE_MAX = 500;

    private final EquipmentService equipment;

    EquipmentController(EquipmentService equipment) {
        this.equipment = equipment;
    }

    /** Cuerpo: {@code branchId}, {@code name}, {@code category?}, {@code status?} (por defecto available). */
    @RequiresAccess(funcion = Funcion.EQUIPO, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<Equipment> create(@RequestBody Map<String, Object> body) {
        Object branch = body.get("branchId");
        if (!(branch instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw new Invalid("branchId", "Indica la sede");
        }
        String status = body.get("status") == null ? "available" : status(body.get("status"));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(equipment.create(ActiveOrganization.id(), n.longValue(), parse(body), status));
    }

    @RequiresAccess(funcion = Funcion.EQUIPO, accion = Accion.CONSULTAR)
    @GetMapping
    List<Equipment> list(@RequestParam(required = false) Long branchId, @RequestParam(required = false) String status,
                         @RequestParam(required = false) String category) {
        String st = status == null || status.isBlank() ? null : status(status);
        String cat = category == null || category.isBlank() ? null : category.trim();
        return equipment.list(ActiveOrganization.id(), new Filter(branchId, st, cat));
    }

    @RequiresAccess(funcion = Funcion.EQUIPO, accion = Accion.CONSULTAR)
    @GetMapping("/{id}")
    ResponseEntity<Equipment> get(@PathVariable long id) {
        return equipment.find(ActiveOrganization.id(), id).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Modifica nombre, categoría, fecha de adquisición e imagen; la sede y el estado no cambian por aquí. */
    @RequiresAccess(funcion = Funcion.EQUIPO, accion = Accion.MODIFICAR)
    @PutMapping("/{id}")
    ResponseEntity<Equipment> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return equipment.update(ActiveOrganization.id(), id, parse(body)).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.EQUIPO, accion = Accion.MODIFICAR)
    @PatchMapping("/{id}/status")
    ResponseEntity<Equipment> setStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return equipment.setStatus(ActiveOrganization.id(), id, status(body.get("status"))).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- validación -------------------------------------------------------------------------

    private static EquipmentData parse(Map<String, Object> body) {
        String name = text(body.get("name"));
        if (name == null || name.isEmpty()) {
            throw new Invalid("name", "El nombre es obligatorio");
        }
        if (name.length() > NAME_MAX) {
            throw new Invalid("name", "El nombre no puede superar " + NAME_MAX + " caracteres");
        }
        return new EquipmentData(name, optional(body, "category", CATEGORY_MAX), date(body.get("acquisitionDate")),
            image(body.get("imageUrl")));
    }

    static String status(Object value) {
        if (!(value instanceof String s) || !STATUSES.contains(s.trim())) {
            throw new Invalid("status", "El estado debe ser available, maintenance, out_of_service o retired");
        }
        return s.trim();
    }

    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof String s && !s.isBlank() ? LocalDate.parse(s.trim()) : null;
        } catch (DateTimeParseException e) {
            throw new Invalid("acquisitionDate", "La fecha debe tener el formato AAAA-MM-DD");
        }
    }

    /** Dirección de imagen: https://… o una ruta del propio sitio que empiece por «/» (igual que el CHECK de la BD). */
    static String image(Object value) {
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

    @ExceptionHandler(InvalidBranchException.class)
    ResponseEntity<Map<String, String>> onInvalidBranch() {
        return ResponseEntity.badRequest()
            .body(error("VALIDATION_ERROR", "branchId", "La sede no existe en el gimnasio o está inactiva"));
    }

    @ExceptionHandler(RetiredException.class)
    ResponseEntity<Map<String, String>> onRetired() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("EQUIPMENT_RETIRED", "status", "El equipo dado de baja no puede cambiar de estado"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

package com.nexfit.organization;

import com.nexfit.organization.BranchService.Branch;
import com.nexfit.organization.BranchService.BranchData;
import com.nexfit.organization.BranchService.BranchNameTakenException;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.RequiresAccess;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Sedes del gimnasio (US-10, NEX-35): /api/branches. El Administrador las crea, modifica y activa o
 * desactiva; Staff, Entrenador y Cliente solo consultan y solo ven las activas, que son las operables
 * (RN-33). El gimnasio sale siempre de la sesión (ActiveOrganization).
 */
@RestController
@RequestMapping("/branches")
class BranchController {

    static final String DEFAULT_COUNTRY = "Colombia";
    private static final int NAME_MAX = 150;
    private static final int ADDRESS_MAX = 150;
    private static final int CITY_MAX = 100;
    private static final int DEPARTMENT_MAX = 100;
    private static final int COUNTRY_MAX = 100;
    private static final int PHONE_MAX = 30;
    private static final int POSTAL_CODE_MAX = 20;
    private static final int SCHEDULE_MAX = 2000;

    private final BranchService branches;

    BranchController(BranchService branches) {
        this.branches = branches;
    }

    @RequiresAccess(funcion = Funcion.SEDES, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<Branch> create(@RequestBody Map<String, Object> body) {
        BranchData data = parse(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(branches.create(ActiveOrganization.id(), data));
    }

    /**
     * Staff, Entrenador y Cliente reciben solo las sedes activas. El Administrador recibe todas y puede
     * filtrar con {@code ?active=true|false}.
     */
    @RequiresAccess(funcion = Funcion.SEDES, accion = Accion.CONSULTAR)
    @GetMapping
    List<Branch> list(@RequestParam(required = false) Boolean active) {
        return branches.list(ActiveOrganization.id(), canManage() ? active : Boolean.TRUE);
    }

    @RequiresAccess(funcion = Funcion.SEDES, accion = Accion.CONSULTAR)
    @GetMapping("/{id}")
    ResponseEntity<Branch> get(@PathVariable long id) {
        return branches.find(ActiveOrganization.id(), id)
            .filter(branch -> branch.active() || canManage())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.SEDES, accion = Accion.MODIFICAR)
    @PutMapping("/{id}")
    ResponseEntity<Branch> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        BranchData data = parse(body);
        return branches.update(ActiveOrganization.id(), id, data)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.SEDES, accion = Accion.ACTIVAR)
    @PatchMapping("/{id}/active")
    ResponseEntity<Branch> setActive(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (!(body.get("active") instanceof Boolean active)) {
            throw new Invalid("active", "Indica si la sede queda activa (true) o inactiva (false)");
        }
        return branches.setActive(ActiveOrganization.id(), id, active)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** ¿Quien consulta puede administrar sedes (ve también las inactivas)? */
    private static boolean canManage() {
        return Permisos.permite(Funcion.SEDES, Accion.MODIFICAR,
            SecurityContextHolder.getContext().getAuthentication().getAuthorities());
    }

    // ---- validación -------------------------------------------------------------------------

    private static BranchData parse(Map<String, Object> body) {
        String name = required(body, "name", "El nombre", NAME_MAX);
        String address1 = required(body, "addressLine1", "La dirección", ADDRESS_MAX);
        String city = required(body, "city", "La ciudad", CITY_MAX);
        String department = required(body, "department", "El departamento", DEPARTMENT_MAX);
        String country = optional(body, "country", COUNTRY_MAX);
        return new BranchData(name, optional(body, "phone", PHONE_MAX), optional(body, "schedule", SCHEDULE_MAX),
            address1, optional(body, "addressLine2", ADDRESS_MAX), city, department,
            optional(body, "postalCode", POSTAL_CODE_MAX), country == null ? DEFAULT_COUNTRY : country);
    }

    private static String required(Map<String, Object> body, String field, String label, int max) {
        String value = text(body.get(field));
        if (value == null || value.isEmpty()) {
            throw new Invalid(field, label + " es obligatorio");
        }
        if (value.length() > max) {
            throw new Invalid(field, label + " no puede superar " + max + " caracteres");
        }
        return value;
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

    @ExceptionHandler(BranchNameTakenException.class)
    ResponseEntity<Map<String, String>> onNameTaken() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("BRANCH_NAME_TAKEN", "name", "Ya existe una sede con ese nombre en el gimnasio"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

package com.nexfit.administration;

import com.nexfit.administration.HealthInsuranceService.NameTakenException;
import com.nexfit.administration.HealthInsuranceService.Provider;
import com.nexfit.shared.Accion;
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
 * Catálogo de EPS: /api/health-insurance-providers. Es global (no depende del gimnasio). Todos los roles con
 * acceso lo consultan para elegir la EPS; crear, renombrar y activar o desactivar (US-18, NEX-30) es solo del
 * Master, que además puede ver las inactivas con {@code ?includeInactive=true}.
 */
@RestController
@RequestMapping("/health-insurance-providers")
class HealthInsuranceController {

    private static final int NAME_MAX = 150;

    private final HealthInsuranceService providers;

    HealthInsuranceController(HealthInsuranceService providers) {
        this.providers = providers;
    }

    /** EPS activas por nombre; el Master recibe también las inactivas si lo pide. */
    @RequiresAccess(funcion = Funcion.CATALOGO_EPS, accion = Accion.CONSULTAR)
    @GetMapping
    List<Provider> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return providers.list(includeInactive && canManage());
    }

    @RequiresAccess(funcion = Funcion.CATALOGO_EPS, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<Provider> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(providers.create(name(body)));
    }

    @RequiresAccess(funcion = Funcion.CATALOGO_EPS, accion = Accion.MODIFICAR)
    @PutMapping("/{id}")
    ResponseEntity<Provider> rename(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return providers.rename(id, name(body)).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.CATALOGO_EPS, accion = Accion.ACTIVAR)
    @PatchMapping("/{id}/active")
    ResponseEntity<Provider> setActive(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (!(body.get("active") instanceof Boolean active)) {
            throw new Invalid("active", "Indica si la EPS queda activa (true) o inactiva (false)");
        }
        return providers.setActive(id, active).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static boolean canManage() {
        return Permisos.permite(Funcion.CATALOGO_EPS, Accion.MODIFICAR,
            SecurityContextHolder.getContext().getAuthentication().getAuthorities());
    }

    private static String name(Map<String, Object> body) {
        if (!(body.get("name") instanceof String raw) || raw.isBlank()) {
            throw new Invalid("name", "El nombre es obligatorio");
        }
        String name = raw.strip();
        if (name.length() > NAME_MAX) {
            throw new Invalid("name", "El nombre no puede superar " + NAME_MAX + " caracteres");
        }
        return name;
    }

    private static class Invalid extends RuntimeException {
        private final String field;

        Invalid(String field, String message) {
            super(message);
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
            .body(error("HEALTH_INSURER_NAME_TAKEN", "name", "Ya existe una EPS con ese nombre"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

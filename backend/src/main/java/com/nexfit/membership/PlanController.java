package com.nexfit.membership;

import com.nexfit.membership.PlanService.Plan;
import com.nexfit.membership.PlanService.PlanData;
import com.nexfit.membership.PlanService.PlanNameTakenException;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.RequiresAccess;
import java.math.BigDecimal;
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
 * Planes de membresía (US-19, NEX-37): /api/membership-plans. El Administrador los crea, modifica y
 * activa o desactiva; Staff y Cliente solo consultan y solo ven los activos, que son los que se
 * pueden contratar (RN-33). El gimnasio sale siempre de la sesión (ActiveOrganization).
 */
@RestController
@RequestMapping("/membership-plans")
class PlanController {

    private static final int NAME_MAX = 100;
    private static final int TEXT_MAX = 2000;
    /** NUMERIC(12,2): hasta 10 dígitos enteros. */
    private static final BigDecimal PRICE_MAX = new BigDecimal("9999999999.99");
    /** Tope técnico (10 años) para que fecha de inicio + duración nunca desborde. */
    private static final int DURATION_MAX_DAYS = 3650;

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    @RequiresAccess(funcion = Funcion.PLANES, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<Plan> create(@RequestBody Map<String, Object> body) {
        PlanData data = parse(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(plans.create(ActiveOrganization.id(), data));
    }

    /**
     * Staff y Cliente reciben solo los planes activos. El Administrador recibe todos y puede filtrar
     * con {@code ?active=true|false}.
     */
    @RequiresAccess(funcion = Funcion.PLANES, accion = Accion.CONSULTAR)
    @GetMapping
    List<Plan> list(@RequestParam(required = false) Boolean active) {
        return plans.list(ActiveOrganization.id(), canManage() ? active : Boolean.TRUE);
    }

    @RequiresAccess(funcion = Funcion.PLANES, accion = Accion.CONSULTAR)
    @GetMapping("/{id}")
    ResponseEntity<Plan> get(@PathVariable long id) {
        return plans.find(ActiveOrganization.id(), id)
            .filter(plan -> plan.active() || canManage())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.PLANES, accion = Accion.MODIFICAR)
    @PutMapping("/{id}")
    ResponseEntity<Plan> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        PlanData data = parse(body);
        return plans.update(ActiveOrganization.id(), id, data)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.PLANES, accion = Accion.ACTIVAR)
    @PatchMapping("/{id}/active")
    ResponseEntity<Plan> setActive(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (!(body.get("active") instanceof Boolean active)) {
            throw new Invalid("active", "Indica si el plan queda activo (true) o inactivo (false)");
        }
        return plans.setActive(ActiveOrganization.id(), id, active)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** ¿Quien consulta puede administrar planes (ve también los inactivos)? */
    private static boolean canManage() {
        return Permisos.permite(Funcion.PLANES, Accion.MODIFICAR,
            SecurityContextHolder.getContext().getAuthentication().getAuthorities());
    }

    // ---- validación -------------------------------------------------------------------------

    private static PlanData parse(Map<String, Object> body) {
        String name = text(body.get("name"));
        if (name == null || name.isEmpty()) {
            throw new Invalid("name", "El nombre es obligatorio");
        }
        if (name.length() > NAME_MAX) {
            throw new Invalid("name", "El nombre no puede superar " + NAME_MAX + " caracteres");
        }
        BigDecimal price = price(body.get("price"));
        int days = durationDays(body.get("durationDays"));
        return new PlanData(name, optionalText(body, "description"), price, days, optionalText(body, "benefits"));
    }

    private static BigDecimal price(Object value) {
        if (value == null) {
            throw new Invalid("price", "El precio es obligatorio");
        }
        BigDecimal price = number(value);
        if (price == null) {
            throw new Invalid("price", "El precio debe ser un número");
        }
        if (price.signum() < 0) {
            throw new Invalid("price", "El precio no puede ser negativo");
        }
        if (price.stripTrailingZeros().scale() > 2) {
            throw new Invalid("price", "El precio admite como máximo 2 decimales");
        }
        if (price.compareTo(PRICE_MAX) > 0) {
            throw new Invalid("price", "El precio es demasiado alto");
        }
        return price.setScale(2);
    }

    private static int durationDays(Object value) {
        if (value == null) {
            throw new Invalid("durationDays", "La duración es obligatoria");
        }
        BigDecimal days = number(value);
        if (days == null || days.stripTrailingZeros().scale() > 0) {
            throw new Invalid("durationDays", "La duración debe ser un número entero de días");
        }
        if (days.signum() <= 0) {
            throw new Invalid("durationDays", "La duración debe ser mayor que 0 días");
        }
        if (days.compareTo(BigDecimal.valueOf(DURATION_MAX_DAYS)) > 0) {
            throw new Invalid("durationDays", "La duración no puede superar " + DURATION_MAX_DAYS + " días");
        }
        return days.intValueExact();
    }

    private static String optionalText(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        String text = text(value);
        if (text == null) {
            throw new Invalid(field, "El campo " + field + " debe ser texto");
        }
        if (text.length() > TEXT_MAX) {
            throw new Invalid(field, "El campo " + field + " no puede superar " + TEXT_MAX + " caracteres");
        }
        return text.isEmpty() ? null : text;
    }

    /** Valor de texto recortado; null si no es una cadena. */
    private static String text(Object value) {
        return value instanceof String s ? s.trim() : null;
    }

    /** Número JSON como BigDecimal; null si no es un número. */
    private static BigDecimal number(Object value) {
        if (value instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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

    @ExceptionHandler(PlanNameTakenException.class)
    ResponseEntity<Map<String, String>> onNameTaken() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("PLAN_NAME_TAKEN", "name", "Ya existe un plan con ese nombre en el gimnasio"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

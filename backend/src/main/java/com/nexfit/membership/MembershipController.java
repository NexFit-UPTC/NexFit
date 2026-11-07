package com.nexfit.membership;

import com.nexfit.membership.MembershipService.ClientNotFoundException;
import com.nexfit.membership.MembershipService.Contract;
import com.nexfit.membership.MembershipService.Filter;
import com.nexfit.membership.MembershipService.InvalidStateException;
import com.nexfit.membership.MembershipService.Membership;
import com.nexfit.membership.MembershipService.OverlapException;
import com.nexfit.membership.MembershipService.PaymentData;
import com.nexfit.membership.MembershipService.PlanUnavailableException;
import com.nexfit.membership.MembershipService.StartInPastException;
import com.nexfit.payment.PaymentService;
import com.nexfit.payment.PaymentService.MembershipMismatchException;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.RequiresAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Membresías (US-20 a US-25): /api/memberships. El Staff contrata y renueva; Staff y Administrador
 * consultan, suspenden, reactivan y cancelan; el Cliente solo ve las suyas. El gimnasio sale siempre de la
 * sesión (ActiveOrganization).
 */
@RestController
@RequestMapping("/memberships")
class MembershipController {

    private static final Set<String> STATUSES = Set.of("scheduled", "current", "expired", "suspended", "cancelled");
    private static final Set<String> METHODS = Set.of("cash", "card", "transfer", "other");
    private static final int REASON_MAX = 500;
    private static final int REFERENCE_MAX = 150;
    private static final int EXPIRING_MAX_DAYS = 365;
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999.99");

    private final MembershipService memberships;
    private final PaymentService payments;

    MembershipController(MembershipService memberships, PaymentService payments) {
        this.memberships = memberships;
        this.payments = payments;
    }

    /** Cuerpo: memberId, planId, startDate opcional (hoy por defecto) y payment opcional. */
    @RequiresAccess(funcion = Funcion.MEMBRESIAS, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<Contract> contract(@RequestBody Map<String, Object> body) {
        long orgId = ActiveOrganization.id();
        long memberId = requiredLong(body, "memberId", "Indica el cliente");
        long planId = requiredLong(body, "planId", "Indica el plan");
        LocalDate start = optionalDate(body, "startDate");
        PaymentData payment = payment(body.get("payment"));
        Contract created = memberships.contract(orgId, actorMemberId(orgId), memberId, planId, start, payment);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Renueva con el mismo plan o con {@code planId}; el pago es opcional. */
    @RequiresAccess(funcion = Funcion.MEMBRESIAS, accion = Accion.RENOVAR)
    @PostMapping("/{id}/renew")
    ResponseEntity<Contract> renew(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> data = body == null ? Map.of() : body;
        long orgId = ActiveOrganization.id();
        Long planId = optionalLong(data, "planId");
        PaymentData payment = payment(data.get("payment"));
        return memberships.renew(orgId, actorMemberId(orgId), id, planId, payment)
            .map(c -> ResponseEntity.status(HttpStatus.CREATED).body(c))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Filtros: memberId, status (estado efectivo) y expiringInDays (vigentes que vencen de hoy a hoy + N).
     * El Cliente recibe siempre solo las suyas.
     */
    @RequiresAccess(funcion = Funcion.MEMBRESIAS, accion = Accion.CONSULTAR)
    @GetMapping
    List<Membership> list(@RequestParam(required = false) Long memberId,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) Integer expiringInDays) {
        long orgId = ActiveOrganization.id();
        if (status != null && !STATUSES.contains(status)) {
            throw new Invalid("status", "El estado debe ser scheduled, current, expired, suspended o cancelled");
        }
        if (expiringInDays != null && (expiringInDays < 0 || expiringInDays > EXPIRING_MAX_DAYS)) {
            throw new Invalid("expiringInDays", "Los días deben estar entre 0 y " + EXPIRING_MAX_DAYS);
        }
        Long member = canSeeAll() ? memberId : actorMemberId(orgId);
        return memberships.list(orgId, new Filter(member, status, expiringInDays));
    }

    @RequiresAccess(funcion = Funcion.MEMBRESIAS, accion = Accion.CONSULTAR)
    @GetMapping("/{id}")
    ResponseEntity<Membership> get(@PathVariable long id) {
        long orgId = ActiveOrganization.id();
        return memberships.find(orgId, id)
            .filter(m -> canSeeAll() || m.memberId() == actorMemberId(orgId))
            .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.MEMBRESIAS, accion = Accion.SUSPENDER)
    @PatchMapping("/{id}/suspend")
    ResponseEntity<Membership> suspend(@PathVariable long id, @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        long orgId = ActiveOrganization.id();
        String reason = reason(body, "El motivo de la suspensión es obligatorio");
        return memberships.suspend(orgId, actorUserId(), actorMemberId(orgId), id, reason, request.getRemoteAddr())
            .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.MEMBRESIAS, accion = Accion.SUSPENDER)
    @PatchMapping("/{id}/reactivate")
    ResponseEntity<Membership> reactivate(@PathVariable long id, @RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        long orgId = ActiveOrganization.id();
        String reason = reason(body, "El motivo de la reactivación es obligatorio");
        return memberships.reactivate(orgId, actorUserId(), actorMemberId(orgId), id, reason, request.getRemoteAddr())
            .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.MEMBRESIAS, accion = Accion.CANCELAR)
    @PatchMapping("/{id}/cancel")
    ResponseEntity<Membership> cancel(@PathVariable long id, @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        long orgId = ActiveOrganization.id();
        String reason = reason(body, "El motivo de la cancelación es obligatorio");
        return memberships.cancel(orgId, actorUserId(), actorMemberId(orgId), id, reason, request.getRemoteAddr())
            .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- sesión -----------------------------------------------------------------------------

    private static boolean canSeeAll() {
        return Permisos.permite(Funcion.MEMBRESIAS, Accion.SUSPENDER,
            SecurityContextHolder.getContext().getAuthentication().getAuthorities());
    }

    private static long actorUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Long id) {
            return id;
        }
        throw new org.springframework.security.access.AccessDeniedException("Sin sesión");
    }

    private long actorMemberId(long orgId) {
        return payments.memberIdOf(orgId, actorUserId())
            .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Sin afiliación"));
    }

    // ---- validación -------------------------------------------------------------------------

    private static PaymentData payment(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new Invalid("payment", "El pago debe ser un objeto");
        }
        BigDecimal amount = null;
        Object value = map.get("amount");
        if (value != null) {
            if (!(value instanceof Number n)) {
                throw new Invalid("payment.amount", "El valor debe ser un número");
            }
            try {
                amount = new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                throw new Invalid("payment.amount", "El valor debe ser un número");
            }
            if (amount.signum() <= 0) {
                throw new Invalid("payment.amount", "El valor debe ser mayor que 0");
            }
            if (amount.stripTrailingZeros().scale() > 2) {
                throw new Invalid("payment.amount", "El valor admite como máximo 2 decimales");
            }
            if (amount.compareTo(AMOUNT_MAX) > 0) {
                throw new Invalid("payment.amount", "El valor es demasiado alto");
            }
            amount = amount.setScale(2);
        }
        Object method = map.get("method");
        if (!(method instanceof String m) || !METHODS.contains(m.trim())) {
            throw new Invalid("payment.method", "El método debe ser cash, card, transfer u other");
        }
        Object reference = map.get("reference");
        String ref = null;
        if (reference != null) {
            if (!(reference instanceof String r)) {
                throw new Invalid("payment.reference", "La referencia debe ser texto");
            }
            ref = r.trim().isEmpty() ? null : r.trim();
            if (ref != null && ref.length() > REFERENCE_MAX) {
                throw new Invalid("payment.reference", "La referencia no puede superar " + REFERENCE_MAX + " caracteres");
            }
        }
        Object status = map.get("status");
        boolean completed = true;
        if (status != null) {
            if ("pending".equals(status)) {
                completed = false;
            } else if (!"completed".equals(status)) {
                throw new Invalid("payment.status", "Un pago se registra como pending o completed");
            }
        }
        return new PaymentData(amount, m.trim(), ref, completed);
    }

    private static String reason(Map<String, Object> body, String message) {
        Object value = body.get("reason");
        String text = value instanceof String s ? s.trim() : null;
        if (text == null || text.isEmpty()) {
            throw new Invalid("reason", message);
        }
        if (text.length() > REASON_MAX) {
            throw new Invalid("reason", "El motivo no puede superar " + REASON_MAX + " caracteres");
        }
        return text;
    }

    private static LocalDate optionalDate(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            try {
                return LocalDate.parse(s.trim());
            } catch (DateTimeParseException e) {
                // cae al error de abajo
            }
        }
        throw new Invalid(field, "La fecha debe tener el formato AAAA-MM-DD");
    }

    private static long requiredLong(Map<String, Object> body, String field, String message) {
        Long value = optionalLong(body, field);
        if (value == null) {
            throw new Invalid(field, message);
        }
        return value;
    }

    private static Long optionalLong(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.longValue();
        }
        throw new Invalid(field, "El campo " + field + " debe ser un número entero");
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

    @ExceptionHandler(ClientNotFoundException.class)
    ResponseEntity<Map<String, String>> onClientNotFound() {
        return ResponseEntity.badRequest()
            .body(error("VALIDATION_ERROR", "memberId", "El cliente no existe en el gimnasio o está inactivo"));
    }

    @ExceptionHandler(PlanUnavailableException.class)
    ResponseEntity<Map<String, String>> onPlanUnavailable() {
        return ResponseEntity.badRequest()
            .body(error("VALIDATION_ERROR", "planId", "El plan no existe en el gimnasio o está inactivo"));
    }

    @ExceptionHandler(StartInPastException.class)
    ResponseEntity<Map<String, String>> onStartInPast() {
        return ResponseEntity.badRequest()
            .body(error("VALIDATION_ERROR", "startDate", "La fecha de inicio no puede ser anterior a hoy"));
    }

    @ExceptionHandler(MembershipMismatchException.class)
    ResponseEntity<Map<String, String>> onMismatch() {
        return ResponseEntity.badRequest()
            .body(error("VALIDATION_ERROR", "payment", "El pago no corresponde a la membresía del cliente"));
    }

    @ExceptionHandler(OverlapException.class)
    ResponseEntity<Map<String, String>> onOverlap() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("MEMBERSHIP_OVERLAP", "startDate", "El cliente ya tiene una membresía que se solapa con ese período"));
    }

    @ExceptionHandler(InvalidStateException.class)
    ResponseEntity<Map<String, String>> onInvalidState(InvalidStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.code(), "status", e.getMessage()));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

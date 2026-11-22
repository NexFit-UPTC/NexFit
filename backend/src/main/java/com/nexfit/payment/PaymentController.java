package com.nexfit.payment;

import com.nexfit.payment.PaymentService.ClientNotFoundException;
import com.nexfit.payment.PaymentService.Filter;
import com.nexfit.payment.PaymentService.MembershipMismatchException;
import com.nexfit.payment.PaymentService.NewPayment;
import com.nexfit.payment.PaymentService.Page;
import com.nexfit.payment.PaymentService.Payment;
import com.nexfit.payment.PaymentService.PaymentNotPendingException;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.RequiresAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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
 * Pagos (US-26, US-28, US-27, US-50): /api/payments. El Staff registra, anula y completa; el
 * Administrador consulta, anula y completa; el Cliente solo ve los suyos. El gimnasio sale siempre de la
 * sesión (ActiveOrganization). No hay ruta de borrado (RN-31) ni pasarela de pago (RN-19).
 */
@RestController
@RequestMapping("/payments")
class PaymentController {

    private static final Set<String> METHODS = Set.of("cash", "card", "transfer", "other");
    private static final Set<String> STATUSES = Set.of("pending", "completed", "voided");
    private static final int TEXT_MAX = 150;
    private static final int REASON_MAX = 500;
    private static final int PAGE_SIZE_MAX = 100;
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999.99");

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @RequiresAccess(funcion = Funcion.PAGOS, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<Payment> create(@RequestBody Map<String, Object> body) {
        long orgId = ActiveOrganization.id();
        long memberId = requiredLong(body, "memberId", "Indica el cliente");
        Long membershipId = optionalLong(body, "membershipId");
        String concept = requiredText(body, "concept", "El concepto es obligatorio", TEXT_MAX);
        BigDecimal amount = amount(body.get("amount"));
        String method = requiredText(body, "method", "El método de pago es obligatorio", 20);
        if (!METHODS.contains(method)) {
            throw new Invalid("method", "El método debe ser cash, card, transfer u other");
        }
        String reference = optionalText(body, "reference", TEXT_MAX);
        String status = body.get("status") == null ? "completed" : requiredText(body, "status", "", 20);
        if (!status.equals("pending") && !status.equals("completed")) {
            throw new Invalid("status", "Un pago se registra como pending o completed");
        }
        Instant paidAt = instant(body.get("paidAt"));
        long actorMember = actorMemberId(orgId);
        Payment created = payments.register(orgId, actorMember, new NewPayment(memberId, membershipId, concept,
            amount, paidAt, method, reference, status.equals("completed")));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Staff y Administrador ven los pagos del gimnasio y filtran por cliente, estado y rango de fechas
     * (inclusive, en la zona del gimnasio); el Cliente ve solo los suyos, anulados incluidos.
     */
    @RequiresAccess(funcion = Funcion.PAGOS, accion = Accion.CONSULTAR)
    @GetMapping
    Page list(@RequestParam(required = false) Long memberId, @RequestParam(required = false) String status,
              @RequestParam(required = false) String from, @RequestParam(required = false) String to,
              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        long orgId = ActiveOrganization.id();
        if (status != null && !STATUSES.contains(status)) {
            throw new Invalid("status", "El estado debe ser pending, completed o voided");
        }
        if (page < 0 || size < 1 || size > PAGE_SIZE_MAX) {
            throw new Invalid("size", "El tamaño de página debe estar entre 1 y " + PAGE_SIZE_MAX);
        }
        ZoneId zone = payments.zoneOf(orgId);
        LocalDate fromDate = date("from", from);
        LocalDate toDate = date("to", to);
        Instant fromInstant = fromDate == null ? null : fromDate.atStartOfDay(zone).toInstant();
        Instant toInstant = toDate == null ? null : toDate.plusDays(1).atStartOfDay(zone).toInstant();
        Long member = memberId;
        if (!canSeeAll()) {
            member = actorMemberId(orgId);
        }
        return payments.list(orgId, new Filter(member, status, fromInstant, toInstant), page, size);
    }

    @RequiresAccess(funcion = Funcion.PAGOS, accion = Accion.ANULAR)
    @PatchMapping("/{id}/void")
    ResponseEntity<Payment> voidPayment(@PathVariable long id, @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        String reason = requiredText(body, "reason", "El motivo de la anulación es obligatorio", REASON_MAX);
        return payments.voidPayment(ActiveOrganization.id(), actorUserId(), id, reason, request.getRemoteAddr())
            .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.PAGOS, accion = Accion.COMPLETAR)
    @PatchMapping("/{id}/complete")
    ResponseEntity<Payment> complete(@PathVariable long id, HttpServletRequest request) {
        long orgId = ActiveOrganization.id();
        return payments.complete(orgId, actorUserId(), actorMemberId(orgId), id, request.getRemoteAddr())
            .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- sesión -----------------------------------------------------------------------------

    private static boolean canSeeAll() {
        return Permisos.permite(Funcion.PAGOS, Accion.ANULAR,
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

    private static BigDecimal amount(Object value) {
        if (!(value instanceof Number n)) {
            throw new Invalid("amount", value == null ? "El valor es obligatorio" : "El valor debe ser un número");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(n.toString());
        } catch (NumberFormatException e) {
            throw new Invalid("amount", "El valor debe ser un número");
        }
        if (amount.signum() <= 0) {
            throw new Invalid("amount", "El valor debe ser mayor que 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new Invalid("amount", "El valor admite como máximo 2 decimales");
        }
        if (amount.compareTo(AMOUNT_MAX) > 0) {
            throw new Invalid("amount", "El valor es demasiado alto");
        }
        return amount.setScale(2);
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return Instant.now();
        }
        if (value instanceof String s) {
            try {
                return Instant.parse(s.trim());
            } catch (DateTimeParseException e) {
                // cae al error de abajo
            }
        }
        throw new Invalid("paidAt", "La fecha de pago debe ser un instante ISO-8601 (ej. 2026-11-07T15:00:00Z)");
    }

    private static LocalDate date(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new Invalid(field, "La fecha debe tener el formato AAAA-MM-DD");
        }
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

    private static String requiredText(Map<String, Object> body, String field, String message, int max) {
        Object value = body.get(field);
        String text = value instanceof String s ? s.trim() : null;
        if (text == null || text.isEmpty()) {
            throw new Invalid(field, message);
        }
        if (text.length() > max) {
            throw new Invalid(field, "El campo " + field + " no puede superar " + max + " caracteres");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> body, String field, int max) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new Invalid(field, "El campo " + field + " debe ser texto");
        }
        String text = s.trim();
        if (text.length() > max) {
            throw new Invalid(field, "El campo " + field + " no puede superar " + max + " caracteres");
        }
        return text.isEmpty() ? null : text;
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
            .body(error("VALIDATION_ERROR", "memberId", "El cliente no existe en este gimnasio"));
    }

    @ExceptionHandler(MembershipMismatchException.class)
    ResponseEntity<Map<String, String>> onMembershipMismatch() {
        return ResponseEntity.badRequest()
            .body(error("VALIDATION_ERROR", "membershipId", "La membresía no existe o pertenece a otro cliente"));
    }

    @ExceptionHandler(PaymentNotPendingException.class)
    ResponseEntity<Map<String, String>> onNotPending() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("PAYMENT_NOT_PENDING", "status", "Solo un pago pendiente puede anularse o completarse"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

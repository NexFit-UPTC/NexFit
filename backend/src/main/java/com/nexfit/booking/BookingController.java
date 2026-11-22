package com.nexfit.booking;

import com.nexfit.booking.BookingService.Booking;
import com.nexfit.booking.BookingService.Filter;
import com.nexfit.booking.BookingService.InvalidReference;
import com.nexfit.booking.BookingService.NotYours;
import com.nexfit.booking.BookingService.Rejected;
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
import org.springframework.security.access.AccessDeniedException;
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
 * Reservas (US-32, US-33, US-34): /api/bookings. El Cliente reserva y cancela solo las suyas; el Staff lo hace
 * a nombre de un cliente de su gimnasio; el Administrador consulta; el Entrenador ve las reservas de sus clases.
 * El gimnasio sale siempre de la sesión (ActiveOrganization).
 */
@RestController
@RequestMapping("/bookings")
class BookingController {

    private static final java.util.Set<String> STATUSES = java.util.Set.of("confirmed", "cancelled");

    private final BookingService bookings;

    BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    /** Cuerpo: {@code classId} y, solo para el Staff, {@code memberId} del cliente. */
    @RequiresAccess(funcion = Funcion.RESERVAS, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<Booking> create(@RequestBody Map<String, Object> body) {
        long orgId = ActiveOrganization.id();
        long classId = requiredLong(body, "classId", "Indica la clase");
        Long requested = optionalLong(body, "memberId");
        long own = actorMemberId(orgId);
        long memberId = own;
        if (isStaff()) {
            if (requested == null) {
                throw new Invalid("memberId", "Indica el cliente");
            }
            memberId = requested;
        } else if (requested != null && requested != own) {
            throw new AccessDeniedException("Un cliente solo reserva para sí mismo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(bookings.create(orgId, memberId, classId));
    }

    /**
     * El Cliente recibe siempre las suyas; el Entrenador, las de sus clases; Staff y Administrador filtran por
     * {@code memberId}, {@code classId} y {@code status}.
     */
    @RequiresAccess(funcion = Funcion.RESERVAS, accion = Accion.CONSULTAR)
    @GetMapping
    List<Booking> list(@RequestParam(required = false) Long memberId, @RequestParam(required = false) Long classId,
                       @RequestParam(required = false) String status) {
        long orgId = ActiveOrganization.id();
        if (status != null && !STATUSES.contains(status)) {
            throw new Invalid("status", "El estado debe ser confirmed o cancelled");
        }
        Long member = memberId;
        Long trainer = null;
        if (isStaffOrAdmin()) {
            // sin restricción adicional
        } else if (isTrainer()) {
            trainer = actorMemberId(orgId);
        } else {
            member = actorMemberId(orgId);
        }
        return bookings.list(orgId, new Filter(member, classId, status, trainer));
    }

    @RequiresAccess(funcion = Funcion.RESERVAS, accion = Accion.CANCELAR)
    @PatchMapping("/{id}/cancel")
    ResponseEntity<Booking> cancel(@PathVariable long id) {
        long orgId = ActiveOrganization.id();
        Long only = isStaff() ? null : actorMemberId(orgId);
        return bookings.cancel(orgId, id, only).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- sesión -----------------------------------------------------------------------------

    private static boolean isStaff() {
        return Permisos.permite(Funcion.CLIENTES, Accion.CREAR, authorities());
    }

    private static boolean isStaffOrAdmin() {
        return isStaff() || Permisos.permite(Funcion.PLANES, Accion.CREAR, authorities());
    }

    private static boolean isTrainer() {
        return Permisos.permite(Funcion.RUTINAS, Accion.CREAR, authorities());
    }

    private static java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> authorities() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities();
    }

    private long actorMemberId(long orgId) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Long userId)) {
            throw new AccessDeniedException("Sin sesión");
        }
        return bookings.memberIdOf(orgId, userId).orElseThrow(() -> new AccessDeniedException("Sin afiliación"));
    }

    // ---- validación -------------------------------------------------------------------------

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

    @ExceptionHandler(InvalidReference.class)
    ResponseEntity<Map<String, String>> onInvalidReference(InvalidReference e) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", e.field(), e.getMessage()));
    }

    @ExceptionHandler(Rejected.class)
    ResponseEntity<Map<String, String>> onRejected(Rejected e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.code(), "status", e.getMessage()));
    }

    @ExceptionHandler(NotYours.class)
    ResponseEntity<Map<String, String>> onNotYours() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(error("ACCESS_DENIED", "id", "Solo puedes cancelar tus propias reservas"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

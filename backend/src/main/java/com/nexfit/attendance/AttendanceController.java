package com.nexfit.attendance;

import com.nexfit.attendance.AttendanceService.CheckIn;
import com.nexfit.attendance.AttendanceService.Filter;
import com.nexfit.attendance.AttendanceService.InvalidReference;
import com.nexfit.attendance.AttendanceService.NotYours;
import com.nexfit.attendance.AttendanceService.Rejected;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.RequiresAccess;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
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
 * Asistencia (US-36, US-37): /api/check-ins. El Cliente registra su propio ingreso y salida; el Staff, los de
 * cualquier cliente de su gimnasio; Administrador y Staff consultan el historial, el Cliente solo el suyo y el
 * Entrenador el de sus alumnos asignados. El gimnasio sale siempre de la sesión (ActiveOrganization).
 */
@RestController
@RequestMapping("/check-ins")
class AttendanceController {

    private final AttendanceService attendance;

    AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    /** Cuerpo: {@code branchId} y, solo para el Staff, {@code memberId} del cliente. */
    @RequiresAccess(funcion = Funcion.ASISTENCIA, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<CheckIn> checkIn(@RequestBody Map<String, Object> body) {
        long orgId = ActiveOrganization.id();
        long branchId = requiredLong(body, "branchId", "Indica la sede");
        Long requested = optionalLong(body, "memberId");
        long own = actorMemberId(orgId);
        if (isStaff()) {
            if (requested == null) {
                throw new Invalid("memberId", "Indica el cliente");
            }
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(attendance.checkIn(orgId, requested, branchId, own));
        }
        if (requested != null && requested != own) {
            throw new AccessDeniedException("Un cliente solo registra su propio ingreso");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(attendance.checkIn(orgId, own, branchId, null));
    }

    @RequiresAccess(funcion = Funcion.ASISTENCIA, accion = Accion.MODIFICAR)
    @PatchMapping("/{id}/check-out")
    ResponseEntity<CheckIn> checkOut(@PathVariable long id) {
        long orgId = ActiveOrganization.id();
        Long only = isStaff() ? null : actorMemberId(orgId);
        return attendance.checkOut(orgId, id, only).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Filtros: {@code memberId}, {@code from} y {@code to} (AAAA-MM-DD inclusivas, en la zona del gimnasio) y
     * {@code open=true} para quienes siguen dentro. El Cliente recibe siempre solo los suyos.
     */
    @RequiresAccess(funcion = Funcion.ASISTENCIA, accion = Accion.CONSULTAR)
    @GetMapping
    List<CheckIn> list(@RequestParam(required = false) Long memberId, @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to, @RequestParam(defaultValue = "false") boolean open) {
        long orgId = ActiveOrganization.id();
        ZoneId zone = attendance.zoneOf(orgId);
        LocalDate fromDate = date("from", from);
        LocalDate toDate = date("to", to);
        OffsetDateTime fromAt = fromDate == null ? null : fromDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toAt = toDate == null ? null : toDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        Long member = memberId;
        Long trainer = null;
        if (isStaffOrAdmin()) {
            // sin restricción adicional
        } else if (isTrainer()) {
            trainer = actorMemberId(orgId);
        } else {
            member = actorMemberId(orgId);
        }
        return attendance.list(orgId, new Filter(member, fromAt, toAt, trainer, open));
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

    private static Collection<? extends GrantedAuthority> authorities() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities();
    }

    private long actorMemberId(long orgId) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Long userId)) {
            throw new AccessDeniedException("Sin sesión");
        }
        return attendance.memberIdOf(orgId, userId).orElseThrow(() -> new AccessDeniedException("Sin afiliación"));
    }

    // ---- validación -------------------------------------------------------------------------

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
            .body(error("ACCESS_DENIED", "id", "Solo puedes registrar tu propia salida"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

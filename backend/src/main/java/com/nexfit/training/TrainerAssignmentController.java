package com.nexfit.training;

import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.RequiresAccess;
import com.nexfit.training.TrainerAssignmentService.AlreadyAssignedException;
import com.nexfit.training.TrainerAssignmentService.Assignment;
import com.nexfit.training.TrainerAssignmentService.InvalidReference;
import com.nexfit.training.TrainerAssignmentService.Student;
import com.nexfit.training.TrainerAssignmentService.Trainer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asignación de alumnos a entrenadores (US-39) y «mis alumnos» (US-40). El Administrador y el Staff asignan y retiran;
 * el Entrenador solo ve lo suyo y no puede asignar. El gimnasio sale siempre de la sesión (ActiveOrganization).
 */
@RestController
class TrainerAssignmentController {

    private final TrainerAssignmentService assignments;

    TrainerAssignmentController(TrainerAssignmentService assignments) {
        this.assignments = assignments;
    }

    /** Entrenadores activos con su número de alumnos; el Entrenador solo se ve a sí mismo. */
    @RequiresAccess(funcion = Funcion.ASIGNACION_ENTRENADOR_ALUMNO, accion = Accion.CONSULTAR)
    @GetMapping("/trainers")
    List<Trainer> trainers() {
        long orgId = ActiveOrganization.id();
        return assignments.trainers(orgId, manages() ? null : actorMemberId(orgId));
    }

    /** Cuerpo: {@code trainerMemberId} y {@code clientMemberId}. Reasignar a un alumno retirado lo reactiva. */
    @RequiresAccess(funcion = Funcion.ASIGNACION_ENTRENADOR_ALUMNO, accion = Accion.CREAR)
    @PostMapping("/trainer-assignments")
    ResponseEntity<Assignment> assign(@RequestBody Map<String, Object> body) {
        long trainer = requiredLong(body, "trainerMemberId", "Indica el entrenador");
        long client = requiredLong(body, "clientMemberId", "Indica el cliente");
        return ResponseEntity.status(HttpStatus.CREATED).body(assignments.assign(ActiveOrganization.id(), trainer, client));
    }

    /** Cuerpo {@code {"active": false}} retira al alumno; {@code true} lo asigna de nuevo. */
    @RequiresAccess(funcion = Funcion.ASIGNACION_ENTRENADOR_ALUMNO, accion = Accion.MODIFICAR)
    @PatchMapping("/trainer-assignments/{trainerMemberId}/{clientMemberId}")
    ResponseEntity<Assignment> setActive(@PathVariable long trainerMemberId, @PathVariable long clientMemberId,
                                         @RequestBody Map<String, Object> body) {
        if (!(body.get("active") instanceof Boolean active)) {
            throw new Invalid("active", "Indica si el alumno queda activo (true) o retirado (false)");
        }
        long orgId = ActiveOrganization.id();
        if (active) {
            return assignments.find(orgId, trainerMemberId, clientMemberId)
                .map(existing -> ResponseEntity.ok(assignments.assign(orgId, trainerMemberId, clientMemberId)))
                .orElseGet(() -> ResponseEntity.notFound().build());
        }
        return assignments.retire(orgId, trainerMemberId, clientMemberId).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Filtros: {@code trainerMemberId}, {@code clientMemberId}, {@code active}. El Entrenador recibe solo las suyas. */
    @RequiresAccess(funcion = Funcion.ASIGNACION_ENTRENADOR_ALUMNO, accion = Accion.CONSULTAR)
    @GetMapping("/trainer-assignments")
    List<Assignment> list(@RequestParam(required = false) Long trainerMemberId,
                          @RequestParam(required = false) Long clientMemberId,
                          @RequestParam(required = false) Boolean active) {
        long orgId = ActiveOrganization.id();
        Long trainer = manages() ? trainerMemberId : Long.valueOf(actorMemberId(orgId));
        return assignments.list(orgId, trainer, clientMemberId, active);
    }

    /**
     * Alumnos activos del Entrenador con nombre, edad, contacto de emergencia principal, rutinas activas y última
     * asistencia. Cada contacto mostrado queda auditado. RUTINAS/CREAR es del Entrenador y de nadie más (RN-24).
     */
    @RequiresAccess(funcion = Funcion.RUTINAS, accion = Accion.CREAR)
    @GetMapping("/my-students")
    List<Student> myStudents(HttpServletRequest request) {
        long orgId = ActiveOrganization.id();
        return assignments.students(orgId, actorMemberId(orgId), actorUserId(), request.getRemoteAddr());
    }

    // ---- sesión -----------------------------------------------------------------------------

    /** ¿Quien consulta asigna alumnos (Administrador o Staff) y por eso ve todo el gimnasio? */
    private static boolean manages() {
        return Permisos.permite(Funcion.ASIGNACION_ENTRENADOR_ALUMNO, Accion.CREAR,
            SecurityContextHolder.getContext().getAuthentication().getAuthorities());
    }

    private static long actorUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            throw new AccessDeniedException("Sin sesión");
        }
        return userId;
    }

    private long actorMemberId(long orgId) {
        return assignments.memberIdOf(orgId, actorUserId()).orElseThrow(() -> new AccessDeniedException("Sin afiliación"));
    }

    // ---- validación -------------------------------------------------------------------------

    private static long requiredLong(Map<String, Object> body, String field, String message) {
        Object value = body.get(field);
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.longValue();
        }
        throw new Invalid(field, message);
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

    @ExceptionHandler(AlreadyAssignedException.class)
    ResponseEntity<Map<String, String>> onAlreadyAssigned() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("ALREADY_ASSIGNED", "clientMemberId", "El cliente ya es alumno de ese entrenador"));
    }

    private static Map<String, String> error(String code, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}

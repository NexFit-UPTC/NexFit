package com.nexfit.administration;

import com.nexfit.administration.AdminAssignmentService.AssignmentResult;
import com.nexfit.administration.AdminAssignmentService.LookupResult;
import com.nexfit.shared.Accion;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creación y asignación del Administrador de un establecimiento (US-09, NEX-34).
 * Solo el Master (matriz 3.7); el orgId viene de la ruta, no del establecimiento activo.
 */
@RestController
@RequestMapping("/establishments/{orgId}/admin")
class AdminAssignmentController {

    private static final int MAX_LENGTH = 150;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");
    private static final Set<String> DOCUMENT_TYPES = Set.of("CC", "CE", "PPT", "PA");

    private final AdminAssignmentService assignments;

    AdminAssignmentController(AdminAssignmentService assignments) {
        this.assignments = assignments;
    }

    @RequiresAccess(funcion = Funcion.CUENTA_ADMINISTRADOR, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<?> assign(@PathVariable long orgId, @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        String documentType = text(body.get("documentType"));
        if (documentType == null || documentType.isEmpty()) {
            return invalid("documentType", "El tipo de documento es obligatorio");
        }
        documentType = documentType.toUpperCase(Locale.ROOT);
        if (!DOCUMENT_TYPES.contains(documentType)) {
            return invalid("documentType", "El tipo de documento no es válido");
        }
        String documentNumber = text(body.get("documentNumber"));
        if (documentNumber == null || documentNumber.isEmpty()) {
            return invalid("documentNumber", "El número de documento es obligatorio");
        }
        if (documentNumber.length() > MAX_LENGTH) {
            return invalid("documentNumber", "El número de documento no puede superar " + MAX_LENGTH + " caracteres");
        }
        String firstName = text(body.get("firstName"));
        if (firstName == null || firstName.isEmpty()) {
            return invalid("firstName", "El nombre es obligatorio");
        }
        if (firstName.length() > MAX_LENGTH) {
            return invalid("firstName", "El nombre no puede superar " + MAX_LENGTH + " caracteres");
        }
        String lastName = text(body.get("lastName"));
        if (lastName == null || lastName.isEmpty()) {
            return invalid("lastName", "El apellido es obligatorio");
        }
        if (lastName.length() > MAX_LENGTH) {
            return invalid("lastName", "El apellido no puede superar " + MAX_LENGTH + " caracteres");
        }
        String birthRaw = text(body.get("birthDate"));
        if (birthRaw == null || birthRaw.isEmpty()) {
            return invalid("birthDate", "La fecha de nacimiento es obligatoria");
        }
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(birthRaw);
        } catch (DateTimeParseException e) {
            return invalid("birthDate", "La fecha de nacimiento no es válida");
        }
        String email = text(body.get("email"));
        if (email == null || email.isEmpty()) {
            return invalid("email", "El correo es obligatorio");
        }
        if (email.length() > MAX_LENGTH || !EMAIL.matcher(email).matches()) {
            return invalid("email", "El correo no es válido");
        }
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        AssignmentResult result = assignments.assign(orgId, actorId, request.getRemoteAddr(), documentType,
            documentNumber, firstName, lastName, birthDate, email);
        if (result.status() == AdminAssignmentService.Status.CREATED) {
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "userId", result.userId(), "temporaryPassword", result.temporaryPassword()));
        }
        if (result.status() == AdminAssignmentService.Status.AFFILIATED) {
            return ResponseEntity.ok(Map.of(
                "userId", result.userId(), "firstName", result.firstName(), "lastName", result.lastName()));
        }
        if (result.status() == AdminAssignmentService.Status.ORG_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Establecimiento no encontrado"));
        }
        if (result.status() == AdminAssignmentService.Status.ORG_INACTIVE) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "El establecimiento está inactivo"));
        }
        if (result.status() == AdminAssignmentService.Status.EMAIL_CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "El correo ya pertenece a otra persona"));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("message", "El documento ya está registrado con otro correo"));
    }

    @RequiresAccess(funcion = Funcion.CUENTA_ADMINISTRADOR, accion = Accion.CONSULTAR)
    @GetMapping("/lookup")
    ResponseEntity<?> lookup(@PathVariable long orgId, @RequestParam String documentType,
                             @RequestParam String documentNumber, HttpServletRequest request) {
        if (documentType == null || !DOCUMENT_TYPES.contains(documentType.toUpperCase(Locale.ROOT))) {
            return invalid("documentType", "El tipo de documento no es válido");
        }
        if (documentNumber == null || documentNumber.isBlank()) {
            return invalid("documentNumber", "El número de documento es obligatorio");
        }
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        LookupResult result = assignments.lookup(orgId, actorId, request.getRemoteAddr(),
            documentType.toUpperCase(Locale.ROOT), documentNumber.trim());
        if (!result.exists()) {
            return ResponseEntity.ok(Map.of("exists", false));
        }
        return ResponseEntity.ok(Map.of("exists", true, "firstName", result.firstName(), "lastName", result.lastName()));
    }

    /** Id del usuario autenticado (el filtro lo guarda como principal); null si no hay sesión. */
    private static Long actorId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }

    /** Valor de texto recortado; null si falta o no es una cadena. */
    private static String text(Object value) {
        return value instanceof String s ? s.trim() : null;
    }

    private static ResponseEntity<Map<String, String>> invalid(String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", "VALIDATION_ERROR");
        error.put("field", field);
        error.put("message", message);
        return ResponseEntity.badRequest().body(error);
    }
}

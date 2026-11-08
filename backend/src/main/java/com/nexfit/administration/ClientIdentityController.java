package com.nexfit.administration;

import com.nexfit.administration.ClientIdentityService.Changes;
import com.nexfit.administration.ClientIdentityService.Identity;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Corregir la identidad de un cliente antes de su activación (US-51, NEX-48): PATCH /api/clients/{memberId}/identity.
 * Solo el Staff (matriz CLIENTES/CREAR); el gimnasio sale de la sesión. Acepta uno o más de documentType,
 * documentNumber, firstName, lastName, birthDate y email; cualquier otro campo se rechaza.
 */
@RestController
@RequestMapping("/clients")
class ClientIdentityController {

    private static final Set<String> FIELDS = Set.of("documentType", "documentNumber", "firstName", "lastName",
        "birthDate", "email");
    private static final Set<String> DOCUMENT_TYPES = Set.of("CC", "CE", "PPT", "PA");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");
    private static final int MAX_NAME = 100;
    private static final int MAX_DOCUMENT = 30;
    private static final int MAX_EMAIL = 150;

    private final ClientIdentityService identities;

    ClientIdentityController(ClientIdentityService identities) {
        this.identities = identities;
    }

    // CLIENTES/MODIFICAR también lo tiene el Cliente (sus propios datos), así que la puerta es CLIENTES/CREAR:
    // en la matriz solo el Staff lo tiene (mismo criterio que usan asistencia y reservas para saber si es Staff).
    @RequiresAccess(funcion = Funcion.CLIENTES, accion = Accion.CREAR)
    @PatchMapping("/{memberId}/identity")
    ResponseEntity<?> correct(@PathVariable long memberId, @RequestBody Map<String, Object> body,
                              HttpServletRequest request) {
        for (String key : body.keySet()) {
            if (!FIELDS.contains(key)) {
                return invalid(key, "Este dato no se puede corregir aquí");
            }
        }
        if (body.isEmpty()) {
            return invalid("body", "Indica al menos un dato de identidad para corregir");
        }
        String documentType = null;
        if (body.containsKey("documentType")) {
            documentType = text(body.get("documentType"));
            documentType = documentType == null ? null : documentType.toUpperCase(Locale.ROOT);
            if (documentType == null || !DOCUMENT_TYPES.contains(documentType)) {
                return invalid("documentType", "El tipo de documento no es válido");
            }
        }
        String documentNumber = null;
        if (body.containsKey("documentNumber")) {
            documentNumber = text(body.get("documentNumber"));
            if (documentNumber == null || documentNumber.isEmpty() || documentNumber.length() > MAX_DOCUMENT) {
                return invalid("documentNumber",
                    "El número de documento es obligatorio y no puede superar 30 caracteres");
            }
        }
        String firstName = null;
        if (body.containsKey("firstName")) {
            firstName = text(body.get("firstName"));
            if (firstName == null || firstName.isEmpty() || firstName.length() > MAX_NAME) {
                return invalid("firstName", "El nombre es obligatorio y no puede superar 100 caracteres");
            }
        }
        String lastName = null;
        if (body.containsKey("lastName")) {
            lastName = text(body.get("lastName"));
            if (lastName == null || lastName.isEmpty() || lastName.length() > MAX_NAME) {
                return invalid("lastName", "El apellido es obligatorio y no puede superar 100 caracteres");
            }
        }
        LocalDate birthDate = null;
        if (body.containsKey("birthDate")) {
            String raw = text(body.get("birthDate"));
            try {
                birthDate = raw == null ? null : LocalDate.parse(raw);
            } catch (DateTimeParseException e) {
                birthDate = null;
            }
            if (birthDate == null) {
                return invalid("birthDate", "La fecha de nacimiento no es válida");
            }
            if (Period.between(birthDate, LocalDate.now()).getYears() < 18) {
                return invalid("birthDate", "La persona debe ser mayor de 18 años");
            }
        }
        String email = null;
        if (body.containsKey("email")) {
            email = text(body.get("email"));
            if (email == null || email.isEmpty() || email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
                return invalid("email", "El correo no es válido");
            }
        }
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        var result = identities.correct(ActiveOrganization.id(), actorId, request.getRemoteAddr(), memberId,
            new Changes(documentType, documentNumber, firstName, lastName, birthDate, email));
        return switch (result.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Cliente no encontrado"));
            case LOCKED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "IDENTITY_LOCKED",
                "message", "La cuenta ya fue activada por su titular o tiene afiliaciones en otros gimnasios: "
                    + "la identidad solo se corrige con una solicitud de corrección verificable"));
            case DOCUMENT_TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "DOCUMENT_ALREADY_EXISTS", "message", "El documento ya pertenece a otra persona"));
            case EMAIL_TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "EMAIL_ALREADY_EXISTS", "message", "El correo ya pertenece a otra persona"));
            case UPDATED -> ResponseEntity.ok(view(memberId, result.identity()));
        };
    }

    private static Map<String, Object> view(long memberId, Identity i) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", memberId);
        body.put("documentType", i.documentType());
        body.put("documentNumber", i.documentNumber());
        body.put("firstName", i.firstName());
        body.put("lastName", i.lastName());
        body.put("birthDate", i.birthDate().toString());
        body.put("email", i.email());
        return body;
    }

    private static Long actorId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }

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

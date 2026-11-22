package com.nexfit.administration;

import com.nexfit.administration.ClientService.CreateResult;
import com.nexfit.administration.ClientService.SearchResult;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro de clientes (US-13, NEX-39). Solo Staff crea clientes y solo Staff
 * busca personas por documento (RF-90); el orgId viene del establecimiento
 * activo, no de la ruta ni del cuerpo.
 */
@RestController
@RequestMapping("/clients")
class ClientController {

    private static final int MAX_LENGTH = 150;
    private static final int MAX_PHONE = 30;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");
    private static final Set<String> DOCUMENT_TYPES = Set.of("CC", "CE", "PPT", "PA");

    private final ClientService clients;

    ClientController(ClientService clients) {
        this.clients = clients;
    }

    @RequiresAccess(funcion = Funcion.CLIENTES, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String documentType = requiredDocumentType(body);
        if (documentType == null) {
            return invalid("documentType", "El tipo de documento no es válido");
        }
        String documentNumber = text(body.get("documentNumber"));
        if (documentNumber == null || documentNumber.isEmpty() || documentNumber.length() > MAX_LENGTH) {
            return invalid("documentNumber", "El número de documento es obligatorio y no puede superar 150 caracteres");
        }
        String firstName = text(body.get("firstName"));
        if (firstName == null || firstName.isEmpty() || firstName.length() > MAX_LENGTH) {
            return invalid("firstName", "El nombre es obligatorio");
        }
        String lastName = text(body.get("lastName"));
        if (lastName == null || lastName.isEmpty() || lastName.length() > MAX_LENGTH) {
            return invalid("lastName", "El apellido es obligatorio");
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
        if (Period.between(birthDate, LocalDate.now()).getYears() < 18) {
            return invalid("birthDate", "La persona debe ser mayor de 18 años");
        }
        String email = text(body.get("email"));
        if (email == null || email.isEmpty() || email.length() > MAX_LENGTH || !EMAIL.matcher(email).matches()) {
            return invalid("email", "El correo no es válido");
        }
        String phone = text(body.get("phone"));
        if (phone == null || phone.isEmpty() || phone.length() > MAX_PHONE) {
            return invalid("phone", "El teléfono es obligatorio y no puede superar 30 caracteres");
        }
        Long epsId = parseEpsId(body.get("epsId"));
        if (epsId == null) {
            return invalid("epsId", "La EPS es obligatoria");
        }
        Object emergencyObject = body.get("emergencyContact");
        if (!(emergencyObject instanceof Map<?, ?> emergency)) {
            return invalid("emergencyContact", "El contacto de emergencia es obligatorio");
        }
        String emergencyName = text(emergency.get("fullName"));
        if (emergencyName == null || emergencyName.isEmpty() || emergencyName.length() > MAX_LENGTH) {
            return invalid("emergencyContact", "El nombre del contacto de emergencia es obligatorio");
        }
        String emergencyRelationship = text(emergency.get("relationship"));
        if (emergencyRelationship == null || emergencyRelationship.isEmpty()
                || emergencyRelationship.length() > MAX_LENGTH) {
            return invalid("emergencyContact", "El parentesco del contacto de emergencia es obligatorio");
        }
        String emergencyPhone = text(emergency.get("phone"));
        if (emergencyPhone == null || emergencyPhone.isEmpty() || emergencyPhone.length() > MAX_PHONE) {
            return invalid("emergencyContact", "El teléfono del contacto de emergencia es obligatorio");
        }
        if (!Boolean.TRUE.equals(body.get("healthConsent"))) {
            return invalid("healthConsent", "La autorización expresa de datos de salud es obligatoria");
        }
        if (!Boolean.TRUE.equals(body.get("privacyAccepted"))) {
            return invalid("privacyAccepted", "La aceptación de la política de tratamiento de datos es obligatoria");
        }
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        long orgId = ActiveOrganization.id();
        CreateResult result = clients.create(orgId, actorId, request.getRemoteAddr(),
            documentType, documentNumber, firstName, lastName, birthDate, email, phone, epsId,
            emergencyName, emergencyRelationship, emergencyPhone);
        if (result.status() == ClientService.Status.CREATED) {
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "userId", result.userId(), "memberId", result.memberId(),
                "temporaryPassword", result.temporaryPassword()));
        }
        if (result.status() == ClientService.Status.AFFILIATED) {
            return ResponseEntity.ok(Map.of(
                "userId", result.userId(), "memberId", result.memberId(),
                "firstName", result.firstName(), "lastName", result.lastName()));
        }
        if (result.status() == ClientService.Status.ORG_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Establecimiento no encontrado"));
        }
        if (result.status() == ClientService.Status.ORG_INACTIVE) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "El establecimiento está inactivo"));
        }
        if (result.status() == ClientService.Status.EPS_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "EPS no encontrada o inactiva"));
        }
        if (result.status() == ClientService.Status.EMAIL_CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "El correo ya pertenece a otra persona"));
        }
        if (result.status() == ClientService.Status.DOCUMENT_EMAIL_MISMATCH) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "El documento ya está registrado con otro correo"));
        }
        if (result.status() == ClientService.Status.ALREADY_MEMBER) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "La persona ya es miembro de este gimnasio"));
        }
        return ResponseEntity.badRequest().body(Map.of("message", "Datos no válidos"));
    }

    @RequiresAccess(funcion = Funcion.CLIENTES, accion = Accion.CONSULTAR)
    @GetMapping("/search")
    ResponseEntity<?> search(@RequestParam String documentType, @RequestParam String documentNumber,
                             @RequestParam(defaultValue = "false") boolean documentVerified,
                             @RequestParam(defaultValue = "false") boolean personPresent,
                             HttpServletRequest request) {
        String type = documentType == null ? null : documentType.trim().toUpperCase(Locale.ROOT);
        if (type == null || !DOCUMENT_TYPES.contains(type)) {
            return invalid("documentType", "El tipo de documento no es válido");
        }
        if (documentNumber == null || documentNumber.trim().isEmpty()) {
            return invalid("documentNumber", "El número de documento es obligatorio");
        }
        if (!documentVerified) {
            return invalid("documentVerified", "Debes confirmar que verificaste el documento de identidad");
        }
        if (!personPresent) {
            return invalid("personPresent", "Debes confirmar que verificaste la presencia de la persona");
        }
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        long orgId = ActiveOrganization.id();
        SearchResult result = clients.search(orgId, actorId, request.getRemoteAddr(),
            type, documentNumber.trim());
        if (result.status() == ClientService.SearchStatus.NOT_STAFF) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "ACCESS_DENIED",
                    "message", "La búsqueda de personas solo está permitida para Staff"));
        }
        if (result.status() == ClientService.SearchStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Persona no encontrada"));
        }
        return ResponseEntity.ok(Map.of("firstName", result.firstName(), "lastName", result.lastName()));
    }

    private String requiredDocumentType(Map<String, Object> body) {
        String type = text(body.get("documentType"));
        if (type == null || type.isEmpty()) {
            return null;
        }
        type = type.toUpperCase(Locale.ROOT);
        return DOCUMENT_TYPES.contains(type) ? type : null;
    }

    private Long parseEpsId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String raw) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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

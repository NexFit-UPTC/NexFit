package com.nexfit.administration;

import com.nexfit.administration.StaffService.StaffDetail;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestión de personal y roles (US-11, NEX-36). Solo el Administrador (matriz 3.7);
 * el orgId viene del establecimiento activo, no de la ruta ni del cuerpo.
 */
@RestController
@RequestMapping("/staff")
class StaffController {

    private static final int MAX_LENGTH = 150;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");
    private static final Set<String> DOCUMENT_TYPES = Set.of("CC", "CE", "PPT", "PA");
    private static final Set<String> STAFF_ROLES = Set.of("staff", "trainer");

    private final StaffService staff;

    StaffController(StaffService staff) {
        this.staff = staff;
    }

    @RequiresAccess(funcion = Funcion.PERSONAL_Y_ROLES, accion = Accion.CREAR)
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
        List<String> roles = parseRoles(body.get("roles"));
        if (roles == null || roles.isEmpty()) {
            return invalid("roles", "Debes indicar al menos un rol: staff o trainer");
        }
        String specialty = optionalText(body.get("specialty"), "specialty");
        String certification = optionalText(body.get("certification"), "certification");
        if (specialty == null && body.get("specialty") != null && !(body.get("specialty") instanceof String)) {
            return invalid("specialty", "La especialidad no es válida");
        }
        LocalDate hireDate = optionalDate(body.get("hireDate"));
        if (body.get("hireDate") != null && hireDate == null) {
            return invalid("hireDate", "La fecha de contratación no es válida");
        }
        if ((specialty != null || certification != null || hireDate != null) && !roles.contains("trainer")) {
            return invalid("roles", "Los datos de entrenador exigen el rol trainer");
        }
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        long orgId = ActiveOrganization.id();
        StaffService.CreateResult result = staff.create(orgId, actorId, request.getRemoteAddr(),
            documentType, documentNumber, firstName, lastName, birthDate, email, roles,
            specialty, certification, hireDate);
        if (result.status() == StaffService.Status.CREATED) {
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "userId", result.userId(), "memberId", result.memberId(),
                "temporaryPassword", result.temporaryPassword()));
        }
        if (result.status() == StaffService.Status.AFFILIATED) {
            return ResponseEntity.ok(Map.of(
                "userId", result.userId(), "memberId", result.memberId(),
                "firstName", result.firstName(), "lastName", result.lastName()));
        }
        if (result.status() == StaffService.Status.ORG_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Establecimiento no encontrado"));
        }
        if (result.status() == StaffService.Status.ORG_INACTIVE) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "El establecimiento está inactivo"));
        }
        if (result.status() == StaffService.Status.EMAIL_CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "El correo ya pertenece a otra persona"));
        }
        if (result.status() == StaffService.Status.DOCUMENT_EMAIL_MISMATCH) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "El documento ya está registrado con otro correo"));
        }
        if (result.status() == StaffService.Status.ALREADY_MEMBER) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "La persona ya es miembro de este gimnasio"));
        }
        return ResponseEntity.badRequest().body(Map.of("message", "Roles no válidos"));
    }

    @RequiresAccess(funcion = Funcion.PERSONAL_Y_ROLES, accion = Accion.CONSULTAR)
    @GetMapping
    ResponseEntity<?> list() {
        long orgId = ActiveOrganization.id();
        return ResponseEntity.ok(staff.list(orgId).stream().map(this::toMap).toList());
    }

    @RequiresAccess(funcion = Funcion.PERSONAL_Y_ROLES, accion = Accion.CONSULTAR)
    @GetMapping("/{memberId}")
    ResponseEntity<?> get(@PathVariable long memberId) {
        long orgId = ActiveOrganization.id();
        var detail = staff.get(orgId, memberId);
        if (detail.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Miembro no encontrado"));
        }
        return ResponseEntity.ok(toMap(detail.get()));
    }

    @RequiresAccess(funcion = Funcion.PERSONAL_Y_ROLES, accion = Accion.MODIFICAR)
    @PatchMapping("/{memberId}")
    ResponseEntity<?> update(@PathVariable long memberId, @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        List<String> roles = parseRoles(body.get("roles"));
        if (roles == null || roles.isEmpty()) {
            return invalid("roles", "Debes indicar al menos un rol: staff o trainer");
        }
        String specialty = optionalText(body.get("specialty"), "specialty");
        String certification = optionalText(body.get("certification"), "certification");
        LocalDate hireDate = optionalDate(body.get("hireDate"));
        if (body.get("hireDate") != null && hireDate == null) {
            return invalid("hireDate", "La fecha de contratación no es válida");
        }
        if ((specialty != null || certification != null || hireDate != null) && !roles.contains("trainer")) {
            return invalid("roles", "Los datos de entrenador exigen el rol trainer");
        }
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        long orgId = ActiveOrganization.id();
        StaffService.UpdateResult result = staff.update(orgId, actorId, request.getRemoteAddr(),
            memberId, roles, specialty, certification, hireDate);
        if (result.status() == StaffService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Miembro no encontrado"));
        }
        if (result.status() == StaffService.Status.INVALID_ROLE) {
            return ResponseEntity.badRequest().body(Map.of("message", "Roles no válidos"));
        }
        return ResponseEntity.ok(toMap(result.detail()));
    }

    @RequiresAccess(funcion = Funcion.PERSONAL_Y_ROLES, accion = Accion.ACTIVAR)
    @PostMapping("/{memberId}/activate")
    ResponseEntity<?> activate(@PathVariable long memberId, HttpServletRequest request) {
        return setActive(memberId, true, request);
    }

    @RequiresAccess(funcion = Funcion.PERSONAL_Y_ROLES, accion = Accion.ACTIVAR)
    @PostMapping("/{memberId}/deactivate")
    ResponseEntity<?> deactivate(@PathVariable long memberId, HttpServletRequest request) {
        return setActive(memberId, false, request);
    }

    private ResponseEntity<?> setActive(long memberId, boolean active, HttpServletRequest request) {
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        long orgId = ActiveOrganization.id();
        StaffService.UpdateResult result = staff.setActive(orgId, actorId, request.getRemoteAddr(), memberId, active);
        if (result.status() == StaffService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Miembro no encontrado"));
        }
        return ResponseEntity.ok(toMap(result.detail()));
    }

    private Map<String, Object> toMap(StaffDetail detail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("memberId", detail.memberId());
        map.put("userId", detail.userId());
        map.put("documentType", detail.documentType());
        map.put("documentNumber", detail.documentNumber());
        map.put("firstName", detail.firstName());
        map.put("lastName", detail.lastName());
        map.put("birthDate", detail.birthDate() == null ? null : detail.birthDate().toString());
        map.put("email", detail.email());
        map.put("active", detail.active());
        map.put("roles", detail.roles());
        if (detail.trainerProfile() == null) {
            map.put("trainerProfile", null);
        } else {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("specialty", detail.trainerProfile().specialty());
            profile.put("certification", detail.trainerProfile().certification());
            profile.put("hireDate", detail.trainerProfile().hireDate() == null
                ? null : detail.trainerProfile().hireDate().toString());
            map.put("trainerProfile", profile);
        }
        return map;
    }

    private String requiredDocumentType(Map<String, Object> body) {
        String type = text(body.get("documentType"));
        if (type == null || type.isEmpty()) {
            return null;
        }
        type = type.toUpperCase(Locale.ROOT);
        return DOCUMENT_TYPES.contains(type) ? type : null;
    }

    private List<String> parseRoles(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> roles = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String raw)) {
                return null;
            }
            String role = raw.trim().toLowerCase(Locale.ROOT);
            if (!STAFF_ROLES.contains(role)) {
                return null;
            }
            if (!roles.contains(role)) {
                roles.add(role);
            }
        }
        return roles;
    }

    private String optionalText(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return null;
        }
        return trimmed;
    }

    private LocalDate optionalDate(Object value) {
        String raw = text(value);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
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

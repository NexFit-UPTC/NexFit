package com.nexfit.auth;

import com.nexfit.auth.AuthService.LoginResult;
import com.nexfit.auth.AuthService.PasswordChangeResult;
import com.nexfit.auth.AuthService.ResetResult;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.AnyAuthenticated;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import com.nexfit.shared.SessionUserFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.nexfit.auth.AuthService.RegisterResult;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rutas de /api/auth: login (pública), logout, cambio de contraseña, selección
 * de
 * establecimiento y restablecimiento asistido de contraseña (con sesión). El
 * prefijo
 * /api es el context-path.
 */
@RestController
@RequestMapping("/auth")
class AuthController {

    record LoginRequest(String email, String password) {
    }

    record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    record SelectEstablishmentRequest(Long organizationId) {
    }

    record EstablishmentDto(long organizationId, String name, List<String> roles) {
    }

    record RegisterRequest(String documentType, String documentNumber, String firstName,
            String lastName, String birthDate, String email, String password) {
    }

    private static final int MAX_LENGTH = 150;
    private static final int NAME_MAX = 100;
    private static final int DOCUMENT_NUMBER_MAX = 30;
    private static final Set<String> DOCUMENT_TYPES = Set.of("CC", "CE", "PPT", "PA");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private final AuthService auth;
    private final RegisterRateLimiter registerLimiter;

    AuthController(AuthService auth, RegisterRateLimiter registerLimiter) {
        this.auth = auth;
        this.registerLimiter = registerLimiter;
    }

    // Declarativo: /auth/login es pública por SecurityConfig, no por esta
    // anotación.
    @AnyAuthenticated
    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        Optional<LoginResult> result = auth.login(body.email(), body.password(), request.getRemoteAddr());
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Credenciales inválidas"));
        }
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        LoginResult r = result.get();
        session.setAttribute(SessionUserFilter.USER_ID_ATTRIBUTE, r.userId());
        // Permite a Spring Session encontrar todas las sesiones de un usuario (para
        // revocarlas al cambiar la contraseña).
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                String.valueOf(r.userId()));
        // US-05: con una sola afiliación activa se elige sola; con varias, el cliente
        // elige.
        if (r.establishments().size() == 1) {
            session.setAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE,
                    r.establishments().get(0).organizationId());
        }
        return ResponseEntity.ok(Map.of("userId", r.userId(),
                "mustChangePassword", r.mustChangePassword(),
                "establishments", r.establishments().stream()
                        .map(e -> new EstablishmentDto(e.organizationId(), e.name(), e.roles()))
                        .toList()));
    }

    /**
     * Auto-registro público (BE-11, NEX-102): crea solo la cuenta, sin roles
     * ni afiliación. La ruta es pública por SecurityConfig; la anotación es
     * declarativa, como en /auth/login.
     */
    @AnyAuthenticated
    @PostMapping("/register")
    ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest body, HttpServletRequest request) {
        if (!registerLimiter.allow(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(RegisterRateLimiter.WINDOW.toSeconds()))
                    .body(Map.of("code", "TOO_MANY_REQUESTS",
                            "message", "Demasiados registros desde esta conexión. Inténtalo de nuevo más tarde"));
        }
        if (body.documentType() == null || body.documentType().isBlank()) {
            return invalid("documentType", "El tipo de documento es obligatorio");
        }
        if (!DOCUMENT_TYPES.contains(body.documentType().trim())) {
            return invalid("documentType", "El tipo de documento no es válido");
        }
        if (body.documentNumber() == null || body.documentNumber().isBlank()) {
            return invalid("documentNumber", "El número de documento es obligatorio");
        }
        if (body.documentNumber().trim().length() > DOCUMENT_NUMBER_MAX) {
            return invalid("documentNumber", "El número de documento no puede superar 30 caracteres");
        }
        if (body.firstName() == null || body.firstName().isBlank()) {
            return invalid("firstName", "El nombre es obligatorio");
        }
        if (body.firstName().trim().length() > NAME_MAX) {
            return invalid("firstName", "El nombre no puede superar 100 caracteres");
        }
        if (body.lastName() == null || body.lastName().isBlank()) {
            return invalid("lastName", "El apellido es obligatorio");
        }
        if (body.lastName().trim().length() > NAME_MAX) {
            return invalid("lastName", "El apellido no puede superar 100 caracteres");
        }
        if (body.birthDate() == null || body.birthDate().isBlank()) {
            return invalid("birthDate", "La fecha de nacimiento es obligatoria");
        }
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(body.birthDate().trim());
        } catch (DateTimeParseException e) {
            return invalid("birthDate", "La fecha debe tener el formato AAAA-MM-DD");
        }
        if (body.email() == null || body.email().isBlank()) {
            return invalid("email", "El correo es obligatorio");
        }
        if (body.email().trim().length() > MAX_LENGTH || !EMAIL.matcher(body.email().trim()).matches()) {
            return invalid("email", "El correo no es válido");
        }
        if (body.password() == null || body.password().length() < AuthService.MIN_PASSWORD_LENGTH) {
            return invalid("password", "La contraseña debe tener al menos "
                    + AuthService.MIN_PASSWORD_LENGTH + " caracteres");
        }
        if (AuthService.tooLong(body.password())) {
            return invalid("password", "La contraseña no puede superar " + AuthService.MAX_PASSWORD_LENGTH
                    + " caracteres");
        }
        RegisterResult result = auth.register(body.documentType().trim(), body.documentNumber().trim(),
                body.firstName().trim(), body.lastName().trim(), birthDate, body.email().trim(),
                body.password(), request.getRemoteAddr());
        return switch (result.status()) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", result.userId()));
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Ya existe una cuenta con esos datos"));
        };
    }

    /**
     * Establecimientos donde el usuario puede operar (US-05, NEX-24). El front los
     * muestra
     * para elegir cuando hay más de uno.
     */
    @AnyAuthenticated
    @GetMapping("/establishments")
    ResponseEntity<List<EstablishmentDto>> establishments(HttpServletRequest request) {
        Long id = userIdOf(request);
        if (id == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(auth.establishments(id).stream()
                .map(e -> new EstablishmentDto(e.organizationId(), e.name(), e.roles()))
                .toList());
    }

    /** Elige (o cambia) el establecimiento activo de la sesión (US-05, NEX-24). */
    @AnyAuthenticated
    @PostMapping("/establishment")
    ResponseEntity<Map<String, Object>> selectEstablishment(@RequestBody SelectEstablishmentRequest body,
            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE);
        if (!(userId instanceof Long id)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body.organizationId() == null || !auth.canOperateIn(id, body.organizationId())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "Establecimiento no disponible para este usuario"));
        }
        session.setAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE, body.organizationId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Cambio de contraseña (temporal o voluntario) de quien tiene la sesión
     * abierta.
     */
    @AnyAuthenticated
    @PostMapping("/password")
    ResponseEntity<Map<String, Object>> changePassword(@RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        Long id = userIdOf(request);
        if (id == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        HttpSession session = request.getSession(false);
        PasswordChangeResult result = auth.changePassword(id, body.currentPassword(), body.newPassword(),
                session.getId());
        return switch (result) {
            case CHANGED -> ResponseEntity.noContent().build();
            case TOO_SHORT -> badRequest("La contraseña debe tener al menos "
                    + AuthService.MIN_PASSWORD_LENGTH + " caracteres");
            case TOO_LONG -> badRequest("La contraseña no puede superar "
                    + AuthService.MAX_PASSWORD_LENGTH + " caracteres");
            case WRONG_CURRENT -> badRequest("La contraseña actual no es correcta");
            case SAME_AS_CURRENT -> badRequest("La contraseña nueva debe ser distinta de la actual");
        };
    }

    /**
     * Restablecimiento asistido de contraseña (US-04, NEX-26): genera una temporal.
     * Si el actor es entrenador o cliente, el interceptor responde 403
     * ACCESS_DENIED antes
     * de entrar al método; el FORBIDDEN del servicio cubre los demás rechazos por
     * rol/alcance.
     */
    @RequiresAccess(funcion = Funcion.CONTRASENAS, accion = Accion.RESTABLECER)
    @PostMapping("/users/{userId}/password-reset")
    ResponseEntity<Map<String, Object>> resetPassword(@PathVariable long userId,
            HttpServletRequest request) {
        Long actorId = userIdOf(request);
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ResetResult result = auth.resetPassword(actorId, activeOrgOrNull(), userId, request.getRemoteAddr());
        return switch (result.status()) {
            case OK -> ResponseEntity.ok(Map.of("temporaryPassword", result.temporaryPassword()));
            case TARGET_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Usuario no encontrado"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "No tienes permiso para restablecer esta contraseña"));
            case MASTER_REQUIRED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Solo el Administrador Master puede restablecer esta cuenta"));
        };
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
    }

    private static ResponseEntity<Map<String, Object>> invalid(String field, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "VALIDATION_ERROR");
        error.put("field", field);
        error.put("message", message);
        return ResponseEntity.badRequest().body(error);
    }

    @AnyAuthenticated
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        Long id = userIdOf(request);
        if (id == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        auth.logout(id, request.getRemoteAddr());
        request.getSession(false).invalidate();
        return ResponseEntity.noContent().build();
    }

    private static Long userIdOf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE);
        return userId instanceof Long id ? id : null;
    }

    /** Establecimiento activo según la convención (NEX-25); null para el Master. */
    private static Long activeOrgOrNull() {
        try {
            return ActiveOrganization.id();
        } catch (AccessDeniedException e) {
            return null;
        }
    }
}
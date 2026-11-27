package com.nexfit.auth;

import com.nexfit.auth.AuthService.LoginResult;
import com.nexfit.auth.AuthService.PasswordChangeResult;
import com.nexfit.auth.AuthService.ResetResult;
import com.nexfit.legal.LegalPolicyService;
import com.nexfit.legal.PolicyDocument;
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

    record GoogleRegistrationRequest(String documentType, String documentNumber, String firstName,
            String lastName, String birthDate, Boolean acceptPolicy) {
    }

    private static final int MAX_LENGTH = 150;
    private static final int NAME_MAX = 100;
    private static final int DOCUMENT_NUMBER_MAX = 30;
    private static final Set<String> DOCUMENT_TYPES = Set.of("CC", "CE", "PPT", "PA");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private final AuthService auth;
    private final RegisterRateLimiter registerLimiter;

    private final LegalPolicyService policy;

    AuthController(AuthService auth, RegisterRateLimiter registerLimiter, LegalPolicyService policy) {
        this.auth = auth;
        this.registerLimiter = registerLimiter;
        this.policy = policy;
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
        LoginResult r = result.get();
        LoginSessions.open(request, r);
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
            return tooManyRegistrations();
        }
        IdentityCheck check = checkIdentity(body.documentType(), body.documentNumber(), body.firstName(),
                body.lastName(), body.birthDate());
        if (check.error() != null) {
            return check.error();
        }
        Identity identity = check.identity();
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
        if (AuthService.isWeak(body.password())) {
            return invalid("password", AuthService.WEAK_PASSWORD_MESSAGE);
        }
        RegisterResult result = auth.register(identity.documentType(), identity.documentNumber(),
                identity.firstName(), identity.lastName(), identity.birthDate(), body.email().trim(),
                body.password(), request.getRemoteAddr());
        return switch (result.status()) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", result.userId()));
            case CONFLICT -> conflict();
        };
    }

    /**
     * Registro con Google, paso 1 (NEX-148): el correo verificado que espera el formulario y la política
     * vigente. Pública por SecurityConfig, como /auth/register; la anotación es declarativa. Sin registro
     * pendiente (o vencido, o sin Google configurado) responde 404.
     */
    @AnyAuthenticated
    @GetMapping("/google-registration")
    ResponseEntity<Map<String, Object>> googleRegistration(HttpServletRequest request) {
        Optional<String> email = GooglePendingRegistration.peek(request);
        if (email.isEmpty()) {
            return noPendingRegistration();
        }
        PolicyDocument policyDocument = policy.document();
        Map<String, Object> policyBody = new LinkedHashMap<>();
        policyBody.put("version", policyDocument.version());
        policyBody.put("effectiveDate", policyDocument.effectiveDate());
        policyBody.put("privacyText", policyDocument.privacyText());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email.get());
        body.put("policy", policyBody);
        return ResponseEntity.ok(body);
    }

    /**
     * Registro con Google, paso 2 (NEX-148): completa los datos y acepta la política. Crea la cuenta Cliente
     * (sin gimnasio ni contraseña propia), abre la sesión como un login y consume el pendiente. El correo sale
     * solo de la sesión; la versión de la política, del servidor. Comparte el tope de uso de /auth/register.
     */
    @AnyAuthenticated
    @PostMapping("/google-registration")
    ResponseEntity<Map<String, Object>> completeGoogleRegistration(@RequestBody GoogleRegistrationRequest body,
            HttpServletRequest request) {
        if (!registerLimiter.allow(request.getRemoteAddr())) {
            return tooManyRegistrations();
        }
        Optional<String> email = GooglePendingRegistration.peek(request);
        if (email.isEmpty()) {
            return noPendingRegistration();
        }
        IdentityCheck check = checkIdentity(body.documentType(), body.documentNumber(), body.firstName(),
                body.lastName(), body.birthDate());
        if (check.error() != null) {
            return check.error();
        }
        if (!Boolean.TRUE.equals(body.acceptPolicy())) {
            return invalid("acceptPolicy", "Debes aceptar la política de tratamiento de datos");
        }
        Identity identity = check.identity();
        RegisterResult result = auth.registerWithGoogle(email.get(), identity.documentType(),
                identity.documentNumber(), identity.firstName(), identity.lastName(), identity.birthDate(),
                policy.currentVersion(), request.getRemoteAddr());
        if (result.status() == AuthService.RegisterStatus.CONFLICT) {
            GooglePendingRegistration.clear(request);
            return conflict();
        }
        // Reemplaza la sesión (y con ella el pendiente), igual que un login con Google.
        LoginSessions.open(request, new LoginResult(result.userId(), false, List.of()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", result.userId()));
    }

    private static ResponseEntity<Map<String, Object>> noPendingRegistration() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "NO_PENDING_REGISTRATION",
                "message", "No hay un registro con Google pendiente"));
    }

    private static ResponseEntity<Map<String, Object>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Ya existe una cuenta con esos datos"));
    }

    private static ResponseEntity<Map<String, Object>> tooManyRegistrations() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(RegisterRateLimiter.WINDOW.toSeconds()))
                .body(Map.of("code", "TOO_MANY_REQUESTS",
                        "message", "Demasiados registros desde esta conexión. Inténtalo de nuevo más tarde"));
    }

    /** Datos personales ya validados y recortados. */
    private record Identity(String documentType, String documentNumber, String firstName, String lastName,
            LocalDate birthDate) {
    }

    /** Resultado de {@link #checkIdentity}: los datos válidos, o la respuesta de error. */
    private record IdentityCheck(Identity identity, ResponseEntity<Map<String, Object>> error) {
    }

    /**
     * Validación de los datos personales que comparten /auth/register y el registro con Google, en el mismo
     * orden y con los mismos mensajes.
     */
    private static IdentityCheck checkIdentity(String documentType, String documentNumber, String firstName,
            String lastName, String birthDateText) {
        if (documentType == null || documentType.isBlank()) {
            return rejected("documentType", "El tipo de documento es obligatorio");
        }
        if (!DOCUMENT_TYPES.contains(documentType.trim())) {
            return rejected("documentType", "El tipo de documento no es válido");
        }
        if (documentNumber == null || documentNumber.isBlank()) {
            return rejected("documentNumber", "El número de documento es obligatorio");
        }
        if (documentNumber.trim().length() > DOCUMENT_NUMBER_MAX) {
            return rejected("documentNumber", "El número de documento no puede superar 30 caracteres");
        }
        if (firstName == null || firstName.isBlank()) {
            return rejected("firstName", "El nombre es obligatorio");
        }
        if (firstName.trim().length() > NAME_MAX) {
            return rejected("firstName", "El nombre no puede superar 100 caracteres");
        }
        if (lastName == null || lastName.isBlank()) {
            return rejected("lastName", "El apellido es obligatorio");
        }
        if (lastName.trim().length() > NAME_MAX) {
            return rejected("lastName", "El apellido no puede superar 100 caracteres");
        }
        if (birthDateText == null || birthDateText.isBlank()) {
            return rejected("birthDate", "La fecha de nacimiento es obligatoria");
        }
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(birthDateText.trim());
        } catch (DateTimeParseException e) {
            return rejected("birthDate", "La fecha debe tener el formato AAAA-MM-DD");
        }
        return new IdentityCheck(new Identity(documentType.trim(), documentNumber.trim(), firstName.trim(),
                lastName.trim(), birthDate), null);
    }

    private static IdentityCheck rejected(String field, String message) {
        return new IdentityCheck(null, invalid(field, message));
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
            case TOO_WEAK -> badRequest(AuthService.WEAK_PASSWORD_MESSAGE);
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
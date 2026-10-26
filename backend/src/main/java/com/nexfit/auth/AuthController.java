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

/**
 * Rutas de /api/auth: login (pública), logout, cambio de contraseña, selección de
 * establecimiento y restablecimiento asistido de contraseña (con sesión). El prefijo
 * /api es el context-path.
 */
@RestController
@RequestMapping("/auth")
class AuthController {

    record LoginRequest(String email, String password) { }

    record ChangePasswordRequest(String currentPassword, String newPassword) { }

    record SelectEstablishmentRequest(Long organizationId) { }

    record EstablishmentDto(long organizationId, String name, List<String> roles) { }

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    // Declarativo: /auth/login es pública por SecurityConfig, no por esta anotación.
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
        // Permite a Spring Session encontrar todas las sesiones de un usuario (para revocarlas al cambiar la contraseña).
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
            String.valueOf(r.userId()));
        // US-05: con una sola afiliación activa se elige sola; con varias, el cliente elige.
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
     * Establecimientos donde el usuario puede operar (US-05, NEX-24). El front los muestra
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

    /** Cambio de contraseña (temporal o voluntario) de quien tiene la sesión abierta. */
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
            case WRONG_CURRENT -> badRequest("La contraseña actual no es correcta");
            case SAME_AS_CURRENT -> badRequest("La contraseña nueva debe ser distinta de la actual");
        };
    }

    /**
     * Restablecimiento asistido de contraseña (US-04, NEX-26): genera una temporal.
     * Si el actor es entrenador o cliente, el interceptor responde 403 ACCESS_DENIED antes
     * de entrar al método; el FORBIDDEN del servicio cubre los demás rechazos por rol/alcance.
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
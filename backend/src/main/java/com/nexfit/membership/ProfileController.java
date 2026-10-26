package com.nexfit.membership;

import com.nexfit.membership.ProfileService.Profile;
import com.nexfit.shared.AnyAuthenticated;
import com.nexfit.shared.Rol;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Información personal propia (US-17, NEX-27): /api/me. Aplica a todos los roles, por eso basta una
 * sesión válida; la protección está en los datos: el usuario sale de la sesión y el establecimiento
 * del activo, nunca de la ruta ni del cuerpo.
 */
@RestController
@RequestMapping("/me")
class ProfileController {

    /** Lo único que el titular puede enviar. Identidad, establecimiento y estado no existen aquí (RN-34). */
    private static final Set<String> EDITABLE = Set.of("phone", "epsId", "emergencyContacts");
    /** EPS y contactos de emergencia solo existen para quien es cliente en el establecimiento activo (RN-25). */
    private static final Set<String> CLIENT_ONLY = Set.of("epsId", "emergencyContacts");

    private final ProfileService profile;

    ProfileController(ProfileService profile) {
        this.profile = profile;
    }

    @AnyAuthenticated
    @GetMapping
    ResponseEntity<Profile> get() {
        return ResponseEntity.ok(profile.get(userId()));
    }

    @AnyAuthenticated
    @PatchMapping
    ResponseEntity<?> update(@RequestBody Map<String, Object> body) {
        if (!EDITABLE.containsAll(body.keySet())) {
            return denied("Solo puedes modificar tu teléfono, tu EPS y tus contactos de emergencia");
        }
        if (body.keySet().stream().anyMatch(CLIENT_ONLY::contains) && !isClient()) {
            return denied("La EPS y los contactos de emergencia solo los modifica el cliente");
        }
        try {
            return ResponseEntity.ok(profile.update(userId(), body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (AccessDeniedException e) {
            return denied("No hay un establecimiento activo con afiliación vigente");
        }
    }

    private static ResponseEntity<Map<String, String>> denied(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "ACCESS_DENIED", "message", message));
    }

    private static long userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long id) {
            return id;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private static boolean isClient() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .flatMap(a -> Rol.deAutoridad(a.getAuthority()).stream())
            .anyMatch(Rol.CLIENT::equals);
    }
}

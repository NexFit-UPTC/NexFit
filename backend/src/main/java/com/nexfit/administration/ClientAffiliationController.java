package com.nexfit.administration;

import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Activar o desactivar la afiliación de un cliente (US-16, NEX-44): POST /api/clients/{memberId}/activate y
 * /deactivate. Staff y Administrador (matriz CLIENTES/ACTIVAR); el gimnasio sale de la sesión.
 */
@RestController
@RequestMapping("/clients")
class ClientAffiliationController {

    private final ClientAffiliationService affiliations;

    ClientAffiliationController(ClientAffiliationService affiliations) {
        this.affiliations = affiliations;
    }

    @RequiresAccess(funcion = Funcion.CLIENTES, accion = Accion.ACTIVAR)
    @PostMapping("/{memberId}/activate")
    ResponseEntity<?> activate(@PathVariable long memberId, HttpServletRequest request) {
        return setActive(memberId, true, request);
    }

    @RequiresAccess(funcion = Funcion.CLIENTES, accion = Accion.ACTIVAR)
    @PostMapping("/{memberId}/deactivate")
    ResponseEntity<?> deactivate(@PathVariable long memberId, HttpServletRequest request) {
        return setActive(memberId, false, request);
    }

    private ResponseEntity<?> setActive(long memberId, boolean active, HttpServletRequest request) {
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        var result = affiliations.setActive(ActiveOrganization.id(), actorId, request.getRemoteAddr(), memberId, active);
        return switch (result.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Cliente no encontrado"));
            case HAS_OTHER_ROLES -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "MEMBER_HAS_OTHER_ROLES",
                "message", "Esta persona tiene otro rol en el gimnasio: su afiliación se gestiona desde Personal"));
            case UPDATED -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("memberId", memberId);
                body.put("active", result.active());
                yield ResponseEntity.ok(body);
            }
        };
    }

    private static Long actorId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}

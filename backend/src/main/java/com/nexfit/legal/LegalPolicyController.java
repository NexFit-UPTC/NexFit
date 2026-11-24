package com.nexfit.legal;

import com.nexfit.shared.Accion;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Texto vigente de la política (NEX-115, DAV-12): GET /api/legal/policy. Es lectura de un
 * documento fijo, no de datos de un titular: no deja evento de auditoría. La regla es
 * CLIENTES/CONSULTAR, la misma de la consulta de clientes, porque la pantalla que lo consume es
 * la afiliación de Staff/Admin.
 */
@RestController
@RequestMapping("/legal")
class LegalPolicyController {

    private final LegalPolicyService policy;

    LegalPolicyController(LegalPolicyService policy) {
        this.policy = policy;
    }

    @RequiresAccess(funcion = Funcion.CLIENTES, accion = Accion.CONSULTAR)
    @GetMapping("/policy")
    ResponseEntity<?> policy() {
        if (actorId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        PolicyDocument doc = policy.document();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", doc.version());
        body.put("effectiveDate", doc.effectiveDate());
        body.put("status", doc.status());
        body.put("privacyText", doc.privacyText());
        body.put("healthText", doc.healthText());
        return ResponseEntity.ok(body);
    }

    private static Long actorId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}

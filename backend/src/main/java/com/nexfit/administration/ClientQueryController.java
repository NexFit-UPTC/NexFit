package com.nexfit.administration;

import com.nexfit.administration.ClientQueryService.DetailResult;
import com.nexfit.administration.ClientQueryService.ListResult;
import com.nexfit.administration.internal.ClientContactRepository.Contact;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Búsqueda y detalle de clientes del gimnasio (US-15, NEX-43): GET /api/clients y GET /api/clients/{memberId}.
 * Solo Staff y Administrador; el gimnasio sale de la sesión, nunca de la ruta ni de un parámetro.
 */
@RestController
@RequestMapping("/clients")
class ClientQueryController {

    private static final int QUERY_MAX = 100;

    private final ClientQueryService clients;

    ClientQueryController(ClientQueryService clients) {
        this.clients = clients;
    }

    /**
     * Página de clientes por apellido y nombre. {@code q} busca por nombre o documento (cada palabra debe
     * aparecer); {@code active} filtra por estado de la afiliación; {@code page} desde 0; {@code size}
     * por defecto 20, máximo 50.
     */
    @RequiresAccess(funcion = Funcion.CLIENTES, accion = Accion.CONSULTAR)
    @GetMapping
    ResponseEntity<?> list(@RequestParam(required = false) String q,
                           @RequestParam(required = false) Boolean active,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        if (q != null && q.length() > QUERY_MAX) {
            return invalid("q", "La búsqueda no puede superar " + QUERY_MAX + " caracteres");
        }
        ListResult result = clients.list(ActiveOrganization.id(), actorId, q, active, page, size);
        if (result.status() == ClientQueryService.Status.FORBIDDEN) {
            return forbidden();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.page().content());
        body.put("page", result.page().page());
        body.put("size", result.page().size());
        body.put("totalElements", result.page().totalElements());
        body.put("totalPages", result.page().totalPages());
        return ResponseEntity.ok(body);
    }

    /** Detalle de un cliente del gimnasio con su EPS y contactos de emergencia; la consulta queda auditada. */
    @RequiresAccess(funcion = Funcion.CLIENTES, accion = Accion.CONSULTAR)
    @GetMapping("/{memberId}")
    ResponseEntity<?> detail(@PathVariable long memberId, HttpServletRequest request) {
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        DetailResult result = clients.detail(ActiveOrganization.id(), actorId, memberId, request.getRemoteAddr());
        if (result.status() == ClientQueryService.Status.FORBIDDEN) {
            return forbidden();
        }
        if (result.status() == ClientQueryService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Cliente no encontrado"));
        }
        var c = result.detail().client();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", c.memberId());
        body.put("documentType", c.documentType());
        body.put("documentNumber", c.documentNumber());
        body.put("firstName", c.firstName());
        body.put("lastName", c.lastName());
        body.put("email", c.email());
        body.put("birthDate", c.birthDate());
        body.put("active", c.active());
        body.put("joinedAt", c.joinedAt());
        body.put("membership", c.membership());
        body.put("phone", c.phone());
        body.put("epsId", c.epsId());
        body.put("epsName", c.epsName());
        List<Map<String, Object>> contacts = new ArrayList<>();
        for (Contact contact : result.detail().emergencyContacts()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", contact.id());
            one.put("fullName", contact.fullName());
            one.put("relationship", contact.relationship());
            one.put("phone", contact.phone());
            one.put("primary", contact.primary());
            contacts.add(one);
        }
        body.put("emergencyContacts", contacts);
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "ACCESS_DENIED",
            "message", "La consulta de clientes solo está permitida para Staff y Administrador"));
    }

    private static Long actorId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }

    private static ResponseEntity<Map<String, String>> invalid(String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", "VALIDATION_ERROR");
        error.put("field", field);
        error.put("message", message);
        return ResponseEntity.badRequest().body(error);
    }
}

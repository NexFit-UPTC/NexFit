package com.nexfit.administration;

import com.nexfit.administration.ClientContactService.GetResult;
import com.nexfit.administration.ClientContactService.UpdateResult;
import com.nexfit.administration.internal.ClientContactRepository.NewContact;
import com.nexfit.shared.Accion;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contacto, EPS y contactos de emergencia de un cliente (US-14, NEX-42).
 * El orgId viene del establecimiento activo, no de la ruta ni del cuerpo.
 * Los datos de identidad no se pueden modificar desde aquí (RN-34).
 */
@RestController
@RequestMapping("/clients")
class ClientContactController {

    private static final int MAX_LENGTH = 150;
    private static final int MAX_RELATIONSHIP = 50;
    private static final Pattern PHONE = Pattern.compile("[0-9+() -]{7,30}");
    private static final Set<String> ALLOWED = Set.of("phone", "epsId", "emergencyContacts", "healthConsent");
    private static final Set<String> IDENTITY = Set.of("documentType", "documentNumber", "firstName",
        "lastName", "birthDate", "email");

    private final ClientContactService contacts;

    ClientContactController(ClientContactService contacts) {
        this.contacts = contacts;
    }

    @RequiresAccess(funcion = Funcion.EPS_Y_CONTACTOS, accion = Accion.CONSULTAR)
    @GetMapping("/{memberId}/contact")
    ResponseEntity<?> get(@PathVariable long memberId, HttpServletRequest request) {
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        long orgId = ActiveOrganization.id();
        GetResult result = contacts.get(orgId, actorId, memberId, request.getRemoteAddr());
        if (result.status() == ClientContactService.GetStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Cliente no encontrado"));
        }
        if (result.status() == ClientContactService.GetStatus.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "ACCESS_DENIED", "message", "No puedes consultar este cliente"));
        }
        return ResponseEntity.ok(contactBody(result.view()));
    }

    @RequiresAccess(funcion = Funcion.EPS_Y_CONTACTOS, accion = Accion.MODIFICAR)
    @PatchMapping("/{memberId}/contact")
    ResponseEntity<?> update(@PathVariable long memberId, @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        Long actorId = actorId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sin sesión"));
        }
        if (body == null || body.isEmpty()) {
            return invalid("body", "No hay nada que actualizar");
        }
        for (String key : body.keySet()) {
            if (IDENTITY.contains(key)) {
                return invalid(key, "Los datos de identidad no se pueden modificar");
            }
            if (!ALLOWED.contains(key)) {
                return invalid(key, "El campo no se puede modificar");
            }
        }
        String phone = null;
        if (body.containsKey("phone")) {
            phone = phone(body.get("phone"));
            if (phone == null) {
                return invalid("phone", "El teléfono debe tener entre 7 y 30 caracteres: dígitos, +, espacios, paréntesis o guiones");
            }
        }
        boolean epsPresent = body.containsKey("epsId");
        Long epsId = null;
        if (epsPresent) {
            epsId = parseEpsId(body.get("epsId"));
            if (epsId == null) {
                return invalid("epsId", "La EPS no puede quedar vacía");
            }
        }
        List<NewContact> newContacts = null;
        if (body.containsKey("emergencyContacts")) {
            newContacts = contacts(body.get("emergencyContacts"));
            if (newContacts == null) {
                return invalid("emergencyContacts", "Debe quedar al menos un contacto de emergencia, con un solo principal");
            }
        }
        boolean healthConsent = Boolean.TRUE.equals(body.get("healthConsent"));
        long orgId = ActiveOrganization.id();
        UpdateResult result = contacts.update(orgId, actorId, memberId, request.getRemoteAddr(),
            phone, epsId, epsPresent, newContacts, healthConsent);
        if (result.status() == ClientContactService.UpdateStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Cliente no encontrado"));
        }
        if (result.status() == ClientContactService.UpdateStatus.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "ACCESS_DENIED", "message", "No puedes modificar este cliente"));
        }
        if (result.status() == ClientContactService.UpdateStatus.EPS_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "La EPS no existe o no está activa"));
        }
        if (result.status() == ClientContactService.UpdateStatus.HEALTH_CONSENT_REQUIRED) {
            return invalid("healthConsent", "Cambiar la EPS exige la autorización expresa de datos de salud");
        }
        return ResponseEntity.ok(contactBody(result.view()));
    }

    private static Map<String, Object> contactBody(ClientContactService.ContactView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", view.memberId());
        body.put("phone", view.phone());
        body.put("epsId", view.epsId());
        body.put("epsName", view.epsName());
        List<Map<String, Object>> contacts = new ArrayList<>();
        for (var c : view.emergencyContacts()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", c.id());
            one.put("fullName", c.fullName());
            one.put("relationship", c.relationship());
            one.put("phone", c.phone());
            one.put("primary", c.primary());
            contacts.add(one);
        }
        body.put("emergencyContacts", contacts);
        return body;
    }

    private static String text(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String phone(Object value) {
        String s = text(value);
        if (s == null || s.length() > 30 || !PHONE.matcher(s).matches()) {
            return null;
        }
        return s;
    }

    private static Long parseEpsId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static List<NewContact> contacts(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty() || items.size() > 10) {
            return null;
        }
        List<NewContact> result = new ArrayList<>();
        int primaries = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                return null;
            }
            String fullName = text(m.get("fullName"));
            String relationship = text(m.get("relationship"));
            String contactPhone = phone(m.get("phone"));
            if (fullName == null || fullName.length() > MAX_LENGTH
                || relationship == null || relationship.length() > MAX_RELATIONSHIP
                || contactPhone == null) {
                return null;
            }
            boolean primary = Boolean.TRUE.equals(m.get("primary"));
            primaries += primary ? 1 : 0;
            result.add(new NewContact(fullName, relationship, contactPhone, primary));
        }
        if (primaries > 1) {
            return null;
        }
        if (primaries == 0) {
            NewContact first = result.get(0);
            result.set(0, new NewContact(first.fullName(), first.relationship(), first.phone(), true));
        }
        return result;
    }

    private static ResponseEntity<Map<String, String>> invalid(String field, String message) {
        return ResponseEntity.badRequest()
            .body(Map.of("code", "VALIDATION_ERROR", "field", field, "message", message));
    }

    private static Long actorId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long id) {
            return id;
        }
        return null;
    }
}

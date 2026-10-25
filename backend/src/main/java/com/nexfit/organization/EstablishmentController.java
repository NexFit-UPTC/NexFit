package com.nexfit.organization;

import com.nexfit.organization.EstablishmentService.Establishment;
import com.nexfit.shared.Accion;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creación de establecimientos (US-06, NEX-29): /api/establishments. Solo el Master (matriz 3.7).
 * El establecimiento nace activo y sin Administrador; no tiene afiliados, por lo que ningún otro
 * gimnasio lo ve (RN-20).
 */
@RestController
@RequestMapping("/establishments")
class EstablishmentController {

    static final String DEFAULT_TIMEZONE = "America/Bogota";
    private static final int MAX_LENGTH = 150;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private final EstablishmentService establishments;

    EstablishmentController(EstablishmentService establishments) {
        this.establishments = establishments;
    }

    @RequiresAccess(funcion = Funcion.ESTABLECIMIENTOS, accion = Accion.CREAR)
    @PostMapping
    ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String name = text(body.get("name"));
        if (name == null || name.isEmpty()) {
            return invalid("name", "El nombre es obligatorio");
        }
        if (name.length() > MAX_LENGTH) {
            return invalid("name", "El nombre no puede superar " + MAX_LENGTH + " caracteres");
        }
        String email = text(body.get("contactEmail"));
        if (email == null || email.isEmpty()) {
            return invalid("contactEmail", "El correo de contacto es obligatorio");
        }
        if (email.length() > MAX_LENGTH || !EMAIL.matcher(email).matches()) {
            return invalid("contactEmail", "El correo de contacto no es válido");
        }
        String timezone = text(body.get("timezone"));
        if (timezone == null || timezone.isEmpty()) {
            timezone = DEFAULT_TIMEZONE;
        } else if (!validZone(timezone)) {
            return invalid("timezone", "La zona horaria no es válida");
        }
        try {
            Establishment created = establishments.create(name, email, timezone);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (InvalidTimezoneException e) {
            return invalid("timezone", "La zona horaria no es válida");
        }
    }

    /** Valor de texto recortado; null si falta o no es una cadena. */
    private static String text(Object value) {
        return value instanceof String s ? s.trim() : null;
    }

    private static boolean validZone(String id) {
        try {
            ZoneId.of(id);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static ResponseEntity<Map<String, String>> invalid(String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", "VALIDATION_ERROR");
        error.put("field", field);
        error.put("message", message);
        return ResponseEntity.badRequest().body(error);
    }
}

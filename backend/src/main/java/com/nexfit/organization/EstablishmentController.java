package com.nexfit.organization;

import com.nexfit.organization.EstablishmentService.Establishment;
import com.nexfit.organization.EstablishmentService.Summary;
import com.nexfit.shared.Accion;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.RequiresAccess;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Establecimientos (US-06, NEX-29; US-07, NEX-32): /api/establishments.
 * La lista y la modificación son solo del Master: la matriz da CONSULTAR también al Admin
 * (para su propio establecimiento), así que la lista verifica además que sea Master.
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

    @RequiresAccess(funcion = Funcion.ESTABLECIMIENTOS, accion = Accion.CONSULTAR)
    @GetMapping
    ResponseEntity<?> list() {
        if (!isMaster()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "ACCESS_DENIED", "message", "No tienes permiso para esta operación"));
        }
        return ResponseEntity.ok(establishments.list());
    }

    @RequiresAccess(funcion = Funcion.ESTABLECIMIENTOS, accion = Accion.CONSULTAR)
    @GetMapping("/{id}")
    ResponseEntity<?> get(@PathVariable long id) {
        if (!isMaster()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "ACCESS_DENIED", "message", "No tienes permiso para esta operación"));
        }
        return establishments.find(id)
            .map(summary -> ResponseEntity.ok((Object) summary))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequiresAccess(funcion = Funcion.ESTABLECIMIENTOS, accion = Accion.MODIFICAR)
    @PutMapping("/{id}")
    ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
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
            return invalid("timezone", "La zona horaria es obligatoria");
        }
        if (!validZone(timezone)) {
            return invalid("timezone", "La zona horaria no es válida");
        }
        try {
            return establishments.update(id, name, email, timezone)
                .map(updated -> ResponseEntity.ok((Object) updated))
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (InvalidTimezoneException e) {
            return invalid("timezone", "La zona horaria no es válida");
        }
    }

    /** Solo el Master puede MODIFICAR establecimientos (matriz 3.7); el resto, no. */
    private static boolean isMaster() {
        return Permisos.permite(Funcion.ESTABLECIMIENTOS, Accion.MODIFICAR,
            SecurityContextHolder.getContext().getAuthentication().getAuthorities());
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

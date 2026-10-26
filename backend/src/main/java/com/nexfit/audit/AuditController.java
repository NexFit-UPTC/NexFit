package com.nexfit.audit;

import com.nexfit.shared.Accion;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.RequiresAccess;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de eventos de auditoría (US-47, NEX-31): solo lectura.
 * No existen rutas de escritura: la auditoría es de solo inserción (RN-40),
 * y la base de datos ya rechaza UPDATE, DELETE y TRUNCATE sobre audit_events.
 */
@RestController
@RequestMapping("/audit/events")
class AuditController {

    private final AuditQueryService audit;

    AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @RequiresAccess(funcion = Funcion.AUDITORIA, accion = Accion.CONSULTAR)
    @GetMapping
    ResponseEntity<?> search(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String type) {
        AuditEventType eventType = null;
        if (type != null) {
            try {
                eventType = AuditEventType.valueOf(type.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("message", "Tipo de evento desconocido: " + type));
            }
        }
        try {
            List<AuditQueryService.AuditEvent> events = audit.search(from, to, userId, eventType);
            return ResponseEntity.ok(events);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "ACCESS_DENIED", "message", "Sin establecimiento activo"));
        }
    }
}

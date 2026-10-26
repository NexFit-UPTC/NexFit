package com.nexfit.audit;

import com.nexfit.audit.internal.AuditEventRepository;
import com.nexfit.shared.ActiveOrganization;
import com.nexfit.shared.Rol;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Consulta de eventos de auditoría (US-47, NEX-31). Solo lectura: nunca escribe
 * en audit_events (RN-40); para registrar está {@link AuditService}.
 */
@Service
public class AuditQueryService {

    public record AuditEvent(long id, Long organizationId, Long actorUserId, String eventType,
                             String entityType, Long entityId, OffsetDateTime occurredAt,
                             String ipAddress, Map<String, Object> metadata) { }

    private final AuditEventRepository repository;

    AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * @param from fecha desde (inclusive, sobre occurred_at), nulo = sin límite
     * @param to fecha hasta (inclusive), nulo = sin límite
     * @param userId actor (actor_user_id), nulo = todos
     * @param type tipo de evento, nulo = todos
     */
    public List<AuditEvent> search(LocalDate from, LocalDate to, Long userId, AuditEventType type) {
        Long organizationId = null;
        if (!isMaster()) {
            // Admin: solo su gimnasio activo. Sin org activa, ActiveOrganization lanza
            // AccessDeniedException y el controlador la convierte en 403.
            organizationId = ActiveOrganization.id();
        }
        // Master: sin filtro de organización → toda la plataforma (criterio 2).
        return repository.search(organizationId, from, to, userId, type == null ? null : type.name())
            .stream()
            .map(e -> new AuditEvent(e.id(), e.organizationId(), e.actorUserId(), e.eventType(),
                e.entityType(), e.entityId(), e.occurredAt(), e.ipAddress(), e.metadata()))
            .toList();
    }

    private static boolean isMaster() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .flatMap(a -> Rol.deAutoridad(a.getAuthority()).stream())
            .anyMatch(Rol.MASTER::equals);
    }
}

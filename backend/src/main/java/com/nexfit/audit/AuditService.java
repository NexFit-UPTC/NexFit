package com.nexfit.audit;

import com.nexfit.audit.internal.AuditEventRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro de auditoría de solo inserción (RN-40). Debe llamarse dentro de la
 * transacción de la operación auditada: si el insert falla, esa operación no se confirma.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;

    AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * @param actorUserId    nulo = sistema
     * @param organizationId nulo = evento global
     * @param ip             IP de origen en texto (nulo si no aplica)
     * @param metadata       datos adicionales; nulo se guarda como objeto vacío
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEventType type, Long actorUserId, Long organizationId,
                       String entityType, Long entityId, String ip, Map<String, Object> metadata) {
        repository.insert(type.name(), actorUserId, organizationId, entityType, entityId, ip,
            metadata == null ? Map.of() : metadata);
    }
}

package com.nexfit.administration;

import com.nexfit.administration.internal.ClientQueryRepository;
import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import com.nexfit.auth.AuthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activar y desactivar la afiliación de un cliente en el gimnasio actual (US-16, NEX-44, RN-33). Solo cambia
 * {@code organization_members.is_active} de ese gimnasio: no toca la identidad global, ni la afiliación en
 * otros gimnasios, ni el historial (membresías, reservas) del cliente. Al desactivar se cierran sus sesiones.
 */
@Service
class ClientAffiliationService {

    private final ClientQueryRepository repo;
    private final AuthService auth;
    private final AuditService audit;

    ClientAffiliationService(ClientQueryRepository repo, AuthService auth, AuditService audit) {
        this.repo = repo;
        this.auth = auth;
        this.audit = audit;
    }

    public enum Status {
        UPDATED, NOT_FOUND, HAS_OTHER_ROLES
    }

    public record Result(Status status, boolean active) { }

    @Transactional
    public Result setActive(long orgId, long actorId, String ip, long memberId, boolean active) {
        var affiliation = repo.findAffiliation(orgId, memberId);
        if (affiliation.isEmpty()) {
            return new Result(Status.NOT_FOUND, false);
        }
        // Quien además es entrenador, staff o administrador se gestiona desde Personal (US-11), no desde aquí.
        if (!affiliation.get().onlyClient()) {
            return new Result(Status.HAS_OTHER_ROLES, affiliation.get().active());
        }
        if (affiliation.get().active() != active) {
            repo.setActive(orgId, memberId, active);
            if (!active) {
                auth.revokeUserSessions(affiliation.get().userId());
            }
            audit.record(AuditEventType.MEMBERSHIP_AFFILIATION_STATUS_CHANGED, actorId, orgId,
                "member", memberId, ip, null);
        }
        return new Result(Status.UPDATED, active);
    }
}

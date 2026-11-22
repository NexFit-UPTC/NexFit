package com.nexfit.administration;

import com.nexfit.administration.internal.ClientContactRepository;
import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contacto, EPS y contactos de emergencia de un cliente (US-14, NEX-42).
 * La compuerta de la matriz (EPS_Y_CONTACTOS) deja pasar a más roles, así que el servicio
 * aplica la regla de datos: consultar lo pueden Staff, Admin y el propio Cliente;
 * modificar lo pueden Staff y el propio Cliente, nunca Admin ni Entrenador.
 * Todo cambio queda solo en la afiliación de este gimnasio.
 */
@Service
class ClientContactService {

    private final ClientContactRepository repo;
    private final AuditService audit;

    ClientContactService(ClientContactRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public enum GetStatus {
        OK, NOT_FOUND, FORBIDDEN
    }

    public enum UpdateStatus {
        OK, NOT_FOUND, FORBIDDEN, EPS_NOT_FOUND, HEALTH_CONSENT_REQUIRED
    }

    public record ContactView(long memberId, String phone, Long epsId, String epsName,
                              List<ClientContactRepository.Contact> emergencyContacts) { }

    public record GetResult(GetStatus status, ContactView view) { }

    public record UpdateResult(UpdateStatus status, ContactView view) { }

    // Sin readOnly: la consulta audita con un INSERT, igual que la búsqueda de la NEX-39.
    @Transactional
    public GetResult get(long orgId, long actorId, long memberId, String ip) {
        var member = repo.findClientMember(orgId, memberId);
        if (member.isEmpty()) {
            return new GetResult(GetStatus.NOT_FOUND, null);
        }
        if (!canRead(orgId, actorId, memberId)) {
            return new GetResult(GetStatus.FORBIDDEN, null);
        }
        audit.record(AuditEventType.HEALTH_CONTACT_DATA_ACCESSED, actorId, orgId,
            "organization_member", memberId, ip, null);
        return new GetResult(GetStatus.OK, view(orgId, member.get()));
    }

    @Transactional
    public UpdateResult update(long orgId, long actorId, long memberId, String ip,
                               String phone, Long epsId, boolean epsPresent,
                               List<ClientContactRepository.NewContact> contacts, boolean healthConsent) {
        var member = repo.findClientMember(orgId, memberId);
        if (member.isEmpty()) {
            return new UpdateResult(UpdateStatus.NOT_FOUND, null);
        }
        if (!canWrite(orgId, actorId, memberId)) {
            return new UpdateResult(UpdateStatus.FORBIDDEN, null);
        }
        if (epsPresent) {
            if (!repo.epsExists(epsId)) {
                return new UpdateResult(UpdateStatus.EPS_NOT_FOUND, null);
            }
            // RN-37: cambiar la EPS exige autorización expresa de datos de salud.
            if (!epsId.equals(member.get().epsId()) && !healthConsent) {
                return new UpdateResult(UpdateStatus.HEALTH_CONSENT_REQUIRED, null);
            }
        }
        if (phone != null) {
            repo.updatePhone(orgId, memberId, phone);
        }
        if (epsPresent) {
            boolean changed = !epsId.equals(member.get().epsId());
            repo.updateEps(orgId, memberId, epsId, changed && healthConsent);
        }
        if (contacts != null) {
            repo.replaceContacts(orgId, memberId, contacts);
        }
        audit.record(AuditEventType.HEALTH_CONTACT_DATA_ACCESSED, actorId, orgId,
            "organization_member", memberId, ip, null);
        var updated = repo.findClientMember(orgId, memberId).orElseThrow();
        return new UpdateResult(UpdateStatus.OK, view(orgId, updated));
    }

    private boolean canRead(long orgId, long actorId, long memberId) {
        if (repo.hasRole(orgId, actorId, "staff") || repo.hasRole(orgId, actorId, "admin")) {
            return true;
        }
        return repo.findOwnClientMemberId(orgId, actorId).map(own -> own == memberId).orElse(false);
    }

    private boolean canWrite(long orgId, long actorId, long memberId) {
        if (repo.hasRole(orgId, actorId, "staff")) {
            return true;
        }
        return repo.findOwnClientMemberId(orgId, actorId).map(own -> own == memberId).orElse(false);
    }

    private ContactView view(long orgId, ClientContactRepository.Member member) {
        return new ContactView(member.id(), member.phone(), member.epsId(), member.epsName(),
            repo.contacts(orgId, member.id()));
    }
}

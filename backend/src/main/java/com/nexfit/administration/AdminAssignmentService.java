package com.nexfit.administration;

import com.nexfit.administration.internal.AdminAssignmentRepository;
import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import com.nexfit.auth.AuthService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asignación del Administrador de un establecimiento (US-09, NEX-34).
 * Un solo Admin garantizado en la misma transacción (RNF-09).
 */
@Service
class AdminAssignmentService {

    private static final int TEMP_PASSWORD_HOURS = 72;

    private final AdminAssignmentRepository repo;
    private final AuthService auth;
    private final AuditService audit;

    AdminAssignmentService(AdminAssignmentRepository repo, AuthService auth, AuditService audit) {
        this.repo = repo;
        this.auth = auth;
        this.audit = audit;
    }

    public enum Status {
        CREATED, AFFILIATED, ORG_NOT_FOUND, ORG_INACTIVE, EMAIL_CONFLICT, DOCUMENT_EMAIL_MISMATCH
    }

    public record AssignmentResult(Status status, Long userId, String temporaryPassword,
                                   String firstName, String lastName) { }

    public record LookupResult(boolean exists, String firstName, String lastName) { }

    @Transactional
    public AssignmentResult assign(long orgId, long actorId, String ip, String documentType,
                                   String documentNumber, String firstName, String lastName,
                                   LocalDate birthDate, String email) {
        var organization = repo.findOrganization(orgId);
        if (organization.isEmpty()) {
            return new AssignmentResult(Status.ORG_NOT_FOUND, null, null, null, null);
        }
        if (!organization.get().active()) {
            return new AssignmentResult(Status.ORG_INACTIVE, null, null, null, null);
        }
        var person = repo.findPersonByDocument(documentType, documentNumber);
        long userId;
        String temporaryPassword = null;
        boolean created;
        if (person.isPresent()) {
            if (!person.get().email().equalsIgnoreCase(email)) {
                return new AssignmentResult(Status.DOCUMENT_EMAIL_MISMATCH, null, null, null, null);
            }
            userId = person.get().id();
            created = false;
        } else {
            if (repo.findUserIdByEmail(email).isPresent()) {
                return new AssignmentResult(Status.EMAIL_CONFLICT, null, null, null, null);
            }
            var temporary = auth.newTemporaryPassword();
            temporaryPassword = temporary.plaintext();
            userId = repo.createUser(documentType, documentNumber, firstName, lastName,
                birthDate, email, temporary.hash(), TEMP_PASSWORD_HOURS);
            created = true;
        }
        var memberId = repo.findMemberId(orgId, userId);
        long member;
        if (memberId.isPresent()) {
            member = memberId.get();
            repo.reactivateMember(member);
        } else {
            member = repo.createMember(orgId, userId);
        }
        List<Long> previousAdmins = repo.removeOtherAdmins(orgId, userId);
        repo.insertAdminRole(member, orgId);
        for (var previous : previousAdmins) {
            auth.revokeUserSessions(previous);
        }
        audit.record(AuditEventType.ADMIN_ASSIGNED, actorId, orgId, "user", userId, ip, null);
        if (created) {
            return new AssignmentResult(Status.CREATED, userId, temporaryPassword, null, null);
        }
        return new AssignmentResult(Status.AFFILIATED, userId, null,
            person.get().firstName(), person.get().lastName());
    }

    @Transactional
    public LookupResult lookup(long orgId, long actorId, String ip,
                               String documentType, String documentNumber) {
        var person = repo.findPersonByDocument(documentType, documentNumber);
        audit.record(AuditEventType.PERSON_LOOKUP_BY_DOCUMENT, actorId, orgId, "user",
            person.map(p -> p.id()).orElse(null), ip, null);
        if (person.isEmpty()) {
            return new LookupResult(false, null, null);
        }
        return new LookupResult(true, person.get().firstName(), person.get().lastName());
    }
}

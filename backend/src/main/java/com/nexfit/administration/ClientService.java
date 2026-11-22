package com.nexfit.administration;

import com.nexfit.administration.internal.ClientRepository;
import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import com.nexfit.auth.AuthService;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro de clientes (US-13, NEX-39).
 * El actor es Staff. Un Entrenador no puede crear clientes: la matriz CLIENTES
 * solo le da CONSULTAR, así que el endpoint de creación le devuelve 403.
 * La búsqueda, en cambio, exige Staff de forma explícita en el servicio,
 * porque la matriz da CONSULTAR a más roles y RF-90 la limita a Staff.
 */
@Service
class ClientService {

    private static final int TEMP_PASSWORD_HOURS = 72;

    private final ClientRepository repo;
    private final AuthService auth;
    private final AuditService audit;

    ClientService(ClientRepository repo, AuthService auth, AuditService audit) {
        this.repo = repo;
        this.auth = auth;
        this.audit = audit;
    }

    public enum Status {
        CREATED, AFFILIATED, NOT_FOUND, ORG_NOT_FOUND, ORG_INACTIVE,
        EMAIL_CONFLICT, DOCUMENT_EMAIL_MISMATCH, ALREADY_MEMBER, EPS_NOT_FOUND
    }

    public enum SearchStatus {
        FOUND, NOT_FOUND, NOT_STAFF
    }

    public record CreateResult(Status status, Long userId, Long memberId, String temporaryPassword,
                               String firstName, String lastName) { }

    public record SearchResult(SearchStatus status, String firstName, String lastName) { }

    @Transactional
    public CreateResult create(long orgId, long actorId, String ip, String documentType,
                               String documentNumber, String firstName, String lastName,
                               LocalDate birthDate, String email, String phone, long epsId,
                               String emergencyName, String emergencyRelationship,
                               String emergencyPhone) {
        var organization = repo.findOrganization(orgId);
        if (organization.isEmpty()) {
            return new CreateResult(Status.ORG_NOT_FOUND, null, null, null, null, null);
        }
        if (!organization.get().active()) {
            return new CreateResult(Status.ORG_INACTIVE, null, null, null, null, null);
        }
        if (!repo.epsExists(epsId)) {
            return new CreateResult(Status.EPS_NOT_FOUND, null, null, null, null, null);
        }
        var person = repo.findPersonByDocument(documentType, documentNumber);
        long userId;
        String temporaryPassword = null;
        boolean created;
        if (person.isPresent()) {
            if (!person.get().email().equalsIgnoreCase(email)) {
                return new CreateResult(Status.DOCUMENT_EMAIL_MISMATCH, null, null, null, null, null);
            }
            userId = person.get().id();
            created = false;
        } else {
            if (repo.findUserIdByEmail(email).isPresent()) {
                return new CreateResult(Status.EMAIL_CONFLICT, null, null, null, null, null);
            }
            var temporary = auth.newTemporaryPassword();
            temporaryPassword = temporary.plaintext();
            userId = repo.createUser(documentType, documentNumber, firstName, lastName,
                birthDate, email, temporary.hash(), TEMP_PASSWORD_HOURS);
            created = true;
        }
        if (repo.findMember(orgId, userId).isPresent()) {
            return new CreateResult(Status.ALREADY_MEMBER, null, null, null, null, null);
        }
        long memberId = repo.createClientMember(orgId, userId, phone, epsId);
        repo.insertRole(memberId, orgId, "client");
        repo.insertEmergencyContact(orgId, memberId, emergencyName, emergencyRelationship, emergencyPhone);
        audit.record(AuditEventType.CLIENT_ACCOUNT_CREATED, actorId, orgId, "user", userId, ip, null);
        if (created) {
            return new CreateResult(Status.CREATED, userId, memberId, temporaryPassword, null, null);
        }
        return new CreateResult(Status.AFFILIATED, userId, memberId, null,
            person.get().firstName(), person.get().lastName());
    }

    @Transactional
    public SearchResult search(long orgId, long actorId, String ip, String documentType,
                               String documentNumber) {
        if (!repo.hasStaffRole(orgId, actorId)) {
            return new SearchResult(SearchStatus.NOT_STAFF, null, null);
        }
        Optional<ClientRepository.Person> person = repo.findPersonByDocument(documentType, documentNumber);
        audit.record(AuditEventType.PERSON_LOOKUP_BY_DOCUMENT, actorId, orgId,
            person.isPresent() ? "user" : null, person.map(ClientRepository.Person::id).orElse(null),
            ip, Map.of("documentVerified", true, "personPresent", true));
        if (person.isEmpty()) {
            return new SearchResult(SearchStatus.NOT_FOUND, null, null);
        }
        return new SearchResult(SearchStatus.FOUND, person.get().firstName(), person.get().lastName());
    }
}

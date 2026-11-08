package com.nexfit.administration;

import com.nexfit.administration.internal.ClientRepository;
import com.nexfit.administration.internal.ClientRepository.IdentityRow;
import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corregir la identidad de un cliente antes de que active su cuenta (US-51, NEX-48, RN-34). Solo procede mientras
 * la cuenta nunca haya iniciado sesión y no tenga afiliaciones en otros gimnasios; después la corrección es un
 * procedimiento manual. El documento y el correo no pueden pertenecer a otra persona (RN-26). Cada cambio se
 * audita con el valor anterior y el nuevo de cada campo modificado.
 */
@Service
class ClientIdentityService {

    private final ClientRepository repo;
    private final AuditService audit;

    ClientIdentityService(ClientRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public enum Status {
        UPDATED, NOT_FOUND, LOCKED, DOCUMENT_TAKEN, EMAIL_TAKEN
    }

    /** Datos que pueden corregirse; los nulos se conservan. */
    public record Changes(String documentType, String documentNumber, String firstName, String lastName,
                          LocalDate birthDate, String email) { }

    public record Identity(String documentType, String documentNumber, String firstName, String lastName,
                           LocalDate birthDate, String email) { }

    public record Result(Status status, Identity identity) { }

    @Transactional
    public Result correct(long orgId, long actorId, String ip, long memberId, Changes changes) {
        var found = repo.lockClientIdentity(orgId, memberId);
        if (found.isEmpty()) {
            return new Result(Status.NOT_FOUND, null);
        }
        IdentityRow now = found.get();
        if (now.loggedInBefore() || now.affiliatedElsewhere()) {
            return new Result(Status.LOCKED, null);
        }
        String type = pick(changes.documentType(), now.documentType());
        String number = pick(changes.documentNumber(), now.documentNumber());
        String first = pick(changes.firstName(), now.firstName());
        String last = pick(changes.lastName(), now.lastName());
        LocalDate birth = changes.birthDate() != null ? changes.birthDate() : now.birthDate();
        String email = pick(changes.email(), now.email());

        Map<String, Object> diff = new LinkedHashMap<>();
        diff(diff, "documentType", now.documentType(), type);
        diff(diff, "documentNumber", now.documentNumber(), number);
        diff(diff, "firstName", now.firstName(), first);
        diff(diff, "lastName", now.lastName(), last);
        diff(diff, "birthDate", now.birthDate(), birth);
        diff(diff, "email", now.email(), email);
        Identity result = new Identity(type, number, first, last, birth, email);
        if (diff.isEmpty()) {
            return new Result(Status.UPDATED, result);
        }
        if (diff.containsKey("documentNumber") && repo.documentTaken(number, now.userId())) {
            return new Result(Status.DOCUMENT_TAKEN, null);
        }
        if (diff.containsKey("email") && repo.emailTaken(email, now.userId())) {
            return new Result(Status.EMAIL_TAKEN, null);
        }
        try {
            repo.updateIdentity(now.userId(), type, number, first, last, birth, email);
        } catch (DataIntegrityViolationException e) {
            // Otra solicitud tomó el documento o el correo entre la comprobación y la escritura (RN-26).
            return new Result(String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase().contains("email")
                ? Status.EMAIL_TAKEN : Status.DOCUMENT_TAKEN, null);
        }
        audit.record(AuditEventType.IDENTITY_CORRECTED, actorId, orgId, "user", now.userId(), ip,
            Map.of("memberId", memberId, "changes", diff));
        return new Result(Status.UPDATED, result);
    }

    private static String pick(String requested, String current) {
        return requested != null ? requested : current;
    }

    private static void diff(Map<String, Object> out, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("before", String.valueOf(before));
            change.put("after", String.valueOf(after));
            out.put(field, change);
        }
    }
}

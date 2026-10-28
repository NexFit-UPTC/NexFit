package com.nexfit.administration;

import com.nexfit.administration.internal.StaffRepository;
import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import com.nexfit.auth.AuthService;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestión de personal y roles (US-11, NEX-36).
 * El rol Administrador nunca se asigna aquí: solo lo asigna el Master (NEX-34).
 * Al modificar, los roles admin o client que ya tenga el miembro no se tocan.
 */
@Service
class StaffService {

    private static final int TEMP_PASSWORD_HOURS = 72;
    private static final Set<String> STAFF_ROLES = Set.of("staff", "trainer");

    private final StaffRepository repo;
    private final AuthService auth;
    private final AuditService audit;

    StaffService(StaffRepository repo, AuthService auth, AuditService audit) {
        this.repo = repo;
        this.auth = auth;
        this.audit = audit;
    }

    public enum Status {
        CREATED, AFFILIATED, UPDATED, NOT_FOUND, ORG_NOT_FOUND, ORG_INACTIVE,
        EMAIL_CONFLICT, DOCUMENT_EMAIL_MISMATCH, ALREADY_MEMBER, INVALID_ROLE
    }

    public record CreateResult(Status status, Long userId, Long memberId, String temporaryPassword,
                               String firstName, String lastName) { }

    public record StaffDetail(long memberId, long userId, String documentType, String documentNumber,
                              String firstName, String lastName, LocalDate birthDate, String email,
                              boolean active, List<String> roles,
                              StaffRepository.TrainerProfile trainerProfile) { }

    public record UpdateResult(Status status, StaffDetail detail) { }

    @Transactional
    public CreateResult create(long orgId, long actorId, String ip, String documentType,
                               String documentNumber, String firstName, String lastName,
                               LocalDate birthDate, String email, List<String> roles,
                               String specialty, String certification, LocalDate hireDate) {
        var organization = repo.findOrganization(orgId);
        if (organization.isEmpty()) {
            return new CreateResult(Status.ORG_NOT_FOUND, null, null, null, null, null);
        }
        if (!organization.get().active()) {
            return new CreateResult(Status.ORG_INACTIVE, null, null, null, null, null);
        }
        if (roles == null || roles.isEmpty() || !STAFF_ROLES.containsAll(roles)) {
            return new CreateResult(Status.INVALID_ROLE, null, null, null, null, null);
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
        long memberId = repo.createMember(orgId, userId);
        for (String role : roles) {
            repo.insertRole(memberId, orgId, role);
        }
        if (roles.contains("trainer")) {
            repo.insertTrainerProfile(memberId, orgId, specialty, certification, hireDate);
        }
        audit.record(AuditEventType.STAFF_ACCOUNT_CREATED, actorId, orgId, "user", userId, ip, null);
        if (created) {
            return new CreateResult(Status.CREATED, userId, memberId, temporaryPassword, null, null);
        }
        return new CreateResult(Status.AFFILIATED, userId, memberId, null,
            person.get().firstName(), person.get().lastName());
    }

    @Transactional(readOnly = true)
    public List<StaffDetail> list(long orgId) {
        return repo.listMembers(orgId).stream().map(this::toDetail).toList();
    }

    @Transactional(readOnly = true)
    public Optional<StaffDetail> get(long orgId, long memberId) {
        return repo.findMemberById(memberId, orgId).map(this::toDetail);
    }

    private StaffDetail toDetail(StaffRepository.MemberInfo member) {
        List<String> roles = repo.rolesOf(member.memberId());
        StaffRepository.TrainerProfile profile = roles.contains("trainer")
            ? repo.trainerProfileOf(member.memberId()).orElse(null)
            : null;
        return new StaffDetail(member.memberId(), member.userId(), member.documentType(),
            member.documentNumber(), member.firstName(), member.lastName(), member.birthDate(),
            member.email(), member.active(), roles, profile);
    }

    @Transactional
    public UpdateResult update(long orgId, long actorId, String ip, long memberId,
                               List<String> roles, String specialty,
                               String certification, LocalDate hireDate) {
        if (roles == null || roles.isEmpty() || !STAFF_ROLES.containsAll(roles)) {
            return new UpdateResult(Status.INVALID_ROLE, null);
        }
        var member = repo.findMemberById(memberId, orgId);
        if (member.isEmpty()) {
            return new UpdateResult(Status.NOT_FOUND, null);
        }
        Set<String> currentStaff = new HashSet<>();
        for (String role : repo.rolesOf(memberId)) {
            if (STAFF_ROLES.contains(role)) {
                currentStaff.add(role);
            }
        }
        Set<String> wanted = new HashSet<>(roles);
        Set<String> toAdd = new HashSet<>(wanted);
        toAdd.removeAll(currentStaff);
        Set<String> toRemove = new HashSet<>(currentStaff);
        toRemove.removeAll(wanted);
        for (String role : toRemove) {
            repo.deleteRole(memberId, role);
            if (role.equals("trainer")) {
                repo.deleteTrainerProfile(memberId);
            }
        }
        for (String role : toAdd) {
            repo.insertRole(memberId, orgId, role);
            if (role.equals("trainer")) {
                repo.insertTrainerProfile(memberId, orgId, specialty, certification, hireDate);
            }
        }
        if (wanted.contains("trainer") && currentStaff.contains("trainer")
            && (specialty != null || certification != null || hireDate != null)) {
            repo.updateTrainerProfile(memberId, specialty, certification, hireDate);
        }
        if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
            audit.record(AuditEventType.ROLE_CHANGED, actorId, orgId, "user",
                member.get().userId(), ip, null);
        }
        return new UpdateResult(Status.UPDATED, get(orgId, memberId).orElseThrow());
    }

    @Transactional
    public UpdateResult setActive(long orgId, long actorId, String ip, long memberId, boolean active) {
        var member = repo.findMemberById(memberId, orgId);
        if (member.isEmpty()) {
            return new UpdateResult(Status.NOT_FOUND, null);
        }
        if (member.get().active() != active) {
            repo.setMemberActive(memberId, active);
            if (!active) {
                auth.revokeUserSessions(member.get().userId());
            }
            audit.record(AuditEventType.MEMBERSHIP_AFFILIATION_STATUS_CHANGED, actorId, orgId,
                "member", memberId, ip, null);
        }
        return new UpdateResult(Status.UPDATED, get(orgId, memberId).orElseThrow());
    }
}

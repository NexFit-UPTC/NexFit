package com.nexfit.administration.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceso a datos de la asignación del Administrador (US-09, NEX-34).
 * Debe ser public: el servicio vive en el paquete padre y una clase
 * package-private en internal no sería visible desde ahí.
 */
@Repository
public class AdminAssignmentRepository {

    private final JdbcClient jdbc;

    AdminAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Organization(long id, boolean active) { }

    public record Person(long id, String firstName, String lastName, String email) { }

    public Optional<Organization> findOrganization(long orgId) {
        return jdbc.sql("SELECT id, is_active FROM organizations WHERE id = :id")
            .param("id", orgId)
            .query((rs, n) -> new Organization(rs.getLong("id"), rs.getBoolean("is_active")))
            .optional();
    }

    public Optional<Person> findPersonByDocument(String documentType, String documentNumber) {
        return jdbc.sql("""
            SELECT id, first_name, last_name, email FROM users
            WHERE document_type = CAST(:type AS document_type) AND document_number = :number
            """)
            .param("type", documentType)
            .param("number", documentNumber)
            .query((rs, n) -> new Person(rs.getLong("id"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("email")))
            .optional();
    }

    public Optional<Long> findUserIdByEmail(String email) {
        return jdbc.sql("SELECT id FROM users WHERE lower(email) = lower(:email)")
            .param("email", email)
            .query((rs, n) -> rs.getLong("id"))
            .optional();
    }

    public long createUser(String documentType, String documentNumber, String firstName,
                           String lastName, LocalDate birthDate, String email,
                           String passwordHash, int tempHours) {
        return jdbc.sql("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date,
                               email, password_hash, must_change_password, temp_password_expires_at,
                               is_platform_admin, is_active)
            VALUES (CAST(:type AS document_type), :number, :first, :last, :birth, :email, :hash,
                    true, now() + make_interval(hours => :hours), false, true)
            RETURNING id
            """)
            .param("type", documentType)
            .param("number", documentNumber)
            .param("first", firstName)
            .param("last", lastName)
            .param("birth", birthDate)
            .param("email", email)
            .param("hash", passwordHash)
            .param("hours", tempHours)
            .query((rs, n) -> rs.getLong("id"))
            .single();
    }

    public Optional<Long> findMemberId(long orgId, long userId) {
        return jdbc.sql("""
            SELECT id FROM organization_members
            WHERE organization_id = :org AND user_id = :user
            """)
            .param("org", orgId)
            .param("user", userId)
            .query((rs, n) -> rs.getLong("id"))
            .optional();
    }

    public long createMember(long orgId, long userId) {
        return jdbc.sql("""
            INSERT INTO organization_members (organization_id, user_id, is_active)
            VALUES (:org, :user, true)
            RETURNING id
            """)
            .param("org", orgId)
            .param("user", userId)
            .query((rs, n) -> rs.getLong("id"))
            .single();
    }

    public void reactivateMember(long memberId) {
        jdbc.sql("UPDATE organization_members SET is_active = true WHERE id = :id")
            .param("id", memberId)
            .update();
    }

    public List<Long> removeOtherAdmins(long orgId, long exceptUserId) {
        return jdbc.sql("""
            DELETE FROM member_roles mr
            USING organization_members om
            WHERE mr.member_id = om.id
              AND mr.organization_id = :org
              AND mr.role = CAST('admin' AS member_role)
              AND om.user_id <> :except
            RETURNING om.user_id
            """)
            .param("org", orgId)
            .param("except", exceptUserId)
            .query((rs, n) -> rs.getLong(1))
            .list();
    }

    public void insertAdminRole(long memberId, long orgId) {
        jdbc.sql("""
            INSERT INTO member_roles (member_id, role, organization_id)
            VALUES (:member, CAST('admin' AS member_role), :org)
            ON CONFLICT (member_id, role) DO NOTHING
            """)
            .param("member", memberId)
            .param("org", orgId)
            .update();
    }
}

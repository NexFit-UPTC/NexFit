package com.nexfit.administration.internal;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceso a datos del registro de clientes (US-13, NEX-39).
 * Debe ser public: el servicio vive en el paquete padre y una clase
 * package-private en internal no sería visible desde ahí.
 */
@Repository
public class ClientRepository {

    private final JdbcClient jdbc;

    ClientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Organization(long id, boolean active) { }

    public record Person(long id, String firstName, String lastName, String email) { }

    public record Member(long id, long userId, boolean active) { }

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

    public Optional<Member> findMember(long orgId, long userId) {
        return jdbc.sql("""
            SELECT id, user_id, is_active FROM organization_members
            WHERE organization_id = :org AND user_id = :user
            """)
            .param("org", orgId)
            .param("user", userId)
            .query((rs, n) -> new Member(rs.getLong("id"), rs.getLong("user_id"),
                rs.getBoolean("is_active")))
            .optional();
    }

    public long createClientMember(long orgId, long userId, String phone, long epsId) {
        return jdbc.sql("""
            INSERT INTO organization_members (organization_id, user_id, phone, eps_id,
                                              privacy_accepted_at, health_consent_at, is_active)
            VALUES (:org, :user, :phone, :eps, now(), now(), true)
            RETURNING id
            """)
            .param("org", orgId)
            .param("user", userId)
            .param("phone", phone)
            .param("eps", epsId)
            .query((rs, n) -> rs.getLong("id"))
            .single();
    }

    public void insertRole(long memberId, long orgId, String role) {
        jdbc.sql("""
            INSERT INTO member_roles (member_id, role, organization_id)
            VALUES (:member, CAST(:role AS member_role), :org)
            ON CONFLICT (member_id, role) DO NOTHING
            """)
            .param("member", memberId)
            .param("role", role)
            .param("org", orgId)
            .update();
    }

    public void insertEmergencyContact(long orgId, long memberId, String fullName,
                                       String relationship, String phone) {
        jdbc.sql("""
            INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
            VALUES (:org, :member, :name, :rel, :phone, true)
            """)
            .param("org", orgId)
            .param("member", memberId)
            .param("name", fullName)
            .param("rel", relationship)
            .param("phone", phone)
            .update();
    }

    public boolean epsExists(long epsId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM health_insurance_providers WHERE id = :id AND is_active)")
            .param("id", epsId)
            .query((rs, n) -> rs.getBoolean(1))
            .single();
    }

    public boolean hasStaffRole(long orgId, long userId) {
        return jdbc.sql("""
            SELECT EXISTS(
              SELECT 1 FROM organization_members om
              JOIN member_roles mr ON mr.member_id = om.id
              WHERE om.organization_id = :org AND om.user_id = :user
                AND mr.role = CAST('staff' AS member_role))
            """)
            .param("org", orgId)
            .param("user", userId)
            .query((rs, n) -> rs.getBoolean(1))
            .single();
    }
}

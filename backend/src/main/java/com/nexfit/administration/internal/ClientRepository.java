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

    /** Identidad de un cliente del gimnasio y lo necesario para saber si todavía se puede corregir (US-51). */
    public record IdentityRow(long userId, String documentType, String documentNumber, String firstName,
                              String lastName, LocalDate birthDate, String email, boolean loggedInBefore,
                              boolean affiliatedElsewhere) { }

    /**
     * El cliente de este gimnasio con su cuenta bloqueada para la transacción (FOR UPDATE): mientras se decide la
     * corrección nadie más la modifica. Vacío si no existe, no es cliente o es de otro gimnasio. «Ya activada» se
     * sabe por su LOGIN auditado (igual que en AuthService); «otras afiliaciones» cuenta cualquier otro gimnasio.
     */
    public Optional<IdentityRow> lockClientIdentity(long orgId, long memberId) {
        return jdbc.sql("""
            SELECT u.id, CAST(u.document_type AS text) AS document_type, u.document_number, u.first_name,
                   u.last_name, u.birth_date, u.email,
                   EXISTS (SELECT 1 FROM audit_events a
                            WHERE a.actor_user_id = u.id AND a.event_type = 'LOGIN') AS logged_in,
                   EXISTS (SELECT 1 FROM organization_members o2
                            WHERE o2.user_id = u.id AND o2.organization_id <> om.organization_id) AS elsewhere
              FROM organization_members om
              JOIN users u ON u.id = om.user_id
             WHERE om.id = :member AND om.organization_id = :org
               AND EXISTS (SELECT 1 FROM member_roles mr WHERE mr.member_id = om.id
                            AND mr.role = CAST('client' AS member_role))
               FOR UPDATE OF u
            """)
            .param("member", memberId)
            .param("org", orgId)
            .query((rs, n) -> new IdentityRow(rs.getLong("id"), rs.getString("document_type"),
                rs.getString("document_number"), rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("birth_date", LocalDate.class), rs.getString("email"),
                rs.getBoolean("logged_in"), rs.getBoolean("elsewhere")))
            .optional();
    }

    public boolean documentTaken(String documentNumber, long exceptUserId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM users WHERE document_number = :n AND id <> :id)")
            .param("n", documentNumber).param("id", exceptUserId)
            .query((rs, n) -> rs.getBoolean(1)).single();
    }

    public boolean emailTaken(String email, long exceptUserId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM users WHERE lower(email) = lower(:e) AND id <> :id)")
            .param("e", email).param("id", exceptUserId)
            .query((rs, n) -> rs.getBoolean(1)).single();
    }

    public void updateIdentity(long userId, String documentType, String documentNumber, String firstName,
                               String lastName, LocalDate birthDate, String email) {
        jdbc.sql("""
            UPDATE users
               SET document_type = CAST(:type AS document_type), document_number = :number,
                   first_name = :first, last_name = :last, birth_date = :birth, email = :email
             WHERE id = :id
            """)
            .param("type", documentType).param("number", documentNumber).param("first", firstName)
            .param("last", lastName).param("birth", birthDate).param("email", email).param("id", userId)
            .update();
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

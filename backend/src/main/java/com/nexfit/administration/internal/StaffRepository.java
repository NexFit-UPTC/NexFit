package com.nexfit.administration.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceso a datos de la gestión de personal (US-11, NEX-36).
 * Debe ser public: el servicio vive en el paquete padre y una clase
 * package-private en internal no sería visible desde ahí.
 */
@Repository
public class StaffRepository {

    private final JdbcClient jdbc;

    StaffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Organization(long id, boolean active) { }

    public record Person(long id, String firstName, String lastName, String email) { }

    public record Member(long id, long userId, boolean active) { }

    public record MemberInfo(long memberId, long userId, String documentType, String documentNumber,
                             String firstName, String lastName, LocalDate birthDate,
                             String email, boolean active) { }

    public record TrainerProfile(String specialty, String certification, LocalDate hireDate) { }

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

    public Optional<MemberInfo> findMemberById(long memberId, long orgId) {
        return jdbc.sql("""
            SELECT om.id AS member_id, om.user_id, u.document_type::text AS document_type,
                   u.document_number, u.first_name, u.last_name, u.birth_date, u.email, om.is_active
            FROM organization_members om JOIN users u ON u.id = om.user_id
            WHERE om.id = :member AND om.organization_id = :org
            """)
            .param("member", memberId)
            .param("org", orgId)
            .query((rs, n) -> new MemberInfo(rs.getLong("member_id"), rs.getLong("user_id"),
                rs.getString("document_type"), rs.getString("document_number"),
                rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("birth_date", LocalDate.class), rs.getString("email"),
                rs.getBoolean("is_active")))
            .optional();
    }

    public List<MemberInfo> listMembers(long orgId) {
        return jdbc.sql("""
            SELECT om.id AS member_id, om.user_id, u.document_type::text AS document_type,
                   u.document_number, u.first_name, u.last_name, u.birth_date, u.email, om.is_active
            FROM organization_members om JOIN users u ON u.id = om.user_id
            WHERE om.organization_id = :org
            ORDER BY u.last_name, u.first_name
            """)
            .param("org", orgId)
            .query((rs, n) -> new MemberInfo(rs.getLong("member_id"), rs.getLong("user_id"),
                rs.getString("document_type"), rs.getString("document_number"),
                rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("birth_date", LocalDate.class), rs.getString("email"),
                rs.getBoolean("is_active")))
            .list();
    }

    public List<String> rolesOf(long memberId) {
        return jdbc.sql("SELECT role::text FROM member_roles WHERE member_id = :member ORDER BY role")
            .param("member", memberId)
            .query(String.class)
            .list();
    }

    public Optional<TrainerProfile> trainerProfileOf(long memberId) {
        return jdbc.sql("""
            SELECT specialty, certification, hire_date FROM trainer_profiles WHERE member_id = :member
            """)
            .param("member", memberId)
            .query((rs, n) -> new TrainerProfile(rs.getString("specialty"),
                rs.getString("certification"), rs.getObject("hire_date", LocalDate.class)))
            .optional();
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

    public void deleteRole(long memberId, String role) {
        jdbc.sql("DELETE FROM member_roles WHERE member_id = :member AND role = CAST(:role AS member_role)")
            .param("member", memberId)
            .param("role", role)
            .update();
    }

    public void insertTrainerProfile(long memberId, long orgId, String specialty,
                                     String certification, LocalDate hireDate) {
        jdbc.sql("""
            INSERT INTO trainer_profiles (member_id, organization_id, specialty, certification, hire_date)
            VALUES (:member, :org, :specialty, :certification, :hire)
            ON CONFLICT (member_id) DO NOTHING
            """)
            .param("member", memberId)
            .param("org", orgId)
            .param("specialty", specialty)
            .param("certification", certification)
            .param("hire", hireDate)
            .update();
    }

    public void updateTrainerProfile(long memberId, String specialty,
                                     String certification, LocalDate hireDate) {
        jdbc.sql("""
            UPDATE trainer_profiles
            SET specialty = COALESCE(:specialty, specialty),
                certification = COALESCE(:certification, certification),
                hire_date = COALESCE(:hire, hire_date)
            WHERE member_id = :member
            """)
            .param("specialty", specialty)
            .param("certification", certification)
            .param("hire", hireDate)
            .param("member", memberId)
            .update();
    }

    public void deleteTrainerProfile(long memberId) {
        jdbc.sql("DELETE FROM trainer_profiles WHERE member_id = :member")
            .param("member", memberId)
            .update();
    }

    public void setMemberActive(long memberId, boolean active) {
        jdbc.sql("UPDATE organization_members SET is_active = :active WHERE id = :id")
            .param("active", active)
            .param("id", memberId)
            .update();
    }
}

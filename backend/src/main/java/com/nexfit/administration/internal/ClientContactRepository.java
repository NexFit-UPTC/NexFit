package com.nexfit.administration.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceso a datos de contacto, EPS y contactos de emergencia de un cliente (US-14, NEX-42).
 * Debe ser public: el servicio vive en el paquete padre y una clase
 * package-private en internal no sería visible desde ahí.
 */
@Repository
public class ClientContactRepository {

    private final JdbcClient jdbc;

    ClientContactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Member(long id, long userId, String phone, Long epsId, String epsName) { }

    public record Contact(long id, String fullName, String relationship, String phone, boolean primary) { }

    public record NewContact(String fullName, String relationship, String phone, boolean primary) { }

    /** Afiliación cliente activa de este gimnasio; vacía si no existe, no es cliente o es de otro gimnasio. */
    public Optional<Member> findClientMember(long orgId, long memberId) {
        return jdbc.sql("""
                SELECT om.id, om.user_id, om.phone, om.eps_id, e.name AS eps_name
                FROM organization_members om
                LEFT JOIN health_insurance_providers e ON e.id = om.eps_id
                WHERE om.id = :member AND om.organization_id = :org AND om.is_active
                  AND EXISTS (SELECT 1 FROM member_roles mr WHERE mr.member_id = om.id
                              AND mr.role = CAST('client' AS member_role))
                """)
            .param("member", memberId)
            .param("org", orgId)
            .query((rs, n) -> new Member(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("phone"), rs.getObject("eps_id", Long.class),
                rs.getString("eps_name")))
            .optional();
    }

    /** Id de la afiliación cliente activa del usuario en este gimnasio, si la tiene. */
    public Optional<Long> findOwnClientMemberId(long orgId, long userId) {
        return jdbc.sql("""
                SELECT om.id FROM organization_members om
                WHERE om.organization_id = :org AND om.user_id = :user AND om.is_active
                  AND EXISTS (SELECT 1 FROM member_roles mr WHERE mr.member_id = om.id
                              AND mr.role = CAST('client' AS member_role))
                """)
            .param("org", orgId)
            .param("user", userId)
            .query(Long.class)
            .optional();
    }

    public boolean hasRole(long orgId, long userId, String role) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM organization_members om
                  JOIN member_roles mr ON mr.member_id = om.id
                  WHERE om.organization_id = :org AND om.user_id = :user
                    AND mr.role = CAST(:role AS member_role))
                """)
            .param("org", orgId)
            .param("user", userId)
            .param("role", role)
            .query((rs, n) -> rs.getBoolean(1))
            .single();
    }

    public boolean epsExists(long epsId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM health_insurance_providers WHERE id = :id AND is_active)")
            .param("id", epsId)
            .query((rs, n) -> rs.getBoolean(1))
            .single();
    }

    public List<Contact> contacts(long orgId, long memberId) {
        return jdbc.sql("""
                SELECT id, full_name, relationship, phone, is_primary FROM emergency_contacts
                WHERE member_id = :m AND organization_id = :org ORDER BY id
                """)
            .param("m", memberId)
            .param("org", orgId)
            .query((rs, n) -> new Contact(rs.getLong("id"), rs.getString("full_name"),
                rs.getString("relationship"), rs.getString("phone"), rs.getBoolean("is_primary")))
            .list();
    }

    public void updatePhone(long orgId, long memberId, String phone) {
        jdbc.sql("UPDATE organization_members SET phone = :v WHERE id = :id AND organization_id = :org")
            .param("v", phone)
            .param("id", memberId)
            .param("org", orgId)
            .update();
    }

    public void updateEps(long orgId, long memberId, long epsId, boolean withConsent) {
        if (withConsent) {
            jdbc.sql("""
                    UPDATE organization_members SET eps_id = :eps, health_consent_at = now()
                    WHERE id = :id AND organization_id = :org
                    """)
                .param("eps", epsId)
                .param("id", memberId)
                .param("org", orgId)
                .update();
        } else {
            jdbc.sql("UPDATE organization_members SET eps_id = :eps WHERE id = :id AND organization_id = :org")
                .param("eps", epsId)
                .param("id", memberId)
                .param("org", orgId)
                .update();
        }
    }

    public void replaceContacts(long orgId, long memberId, List<NewContact> contacts) {
        jdbc.sql("DELETE FROM emergency_contacts WHERE member_id = :m AND organization_id = :org")
            .param("m", memberId)
            .param("org", orgId)
            .update();
        for (NewContact c : contacts) {
            jdbc.sql("""
                    INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
                    VALUES (:org, :m, :name, :rel, :phone, :primary)
                    """)
                .param("org", orgId)
                .param("m", memberId)
                .param("name", c.fullName())
                .param("rel", c.relationship())
                .param("phone", c.phone())
                .param("primary", c.primary())
                .update();
        }
    }
}

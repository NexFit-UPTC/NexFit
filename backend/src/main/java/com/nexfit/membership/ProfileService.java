package com.nexfit.membership;

import com.nexfit.shared.ActiveOrganization;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Información personal propia (US-17, NEX-27). El usuario sale de la sesión y el establecimiento de
 * {@link ActiveOrganization}: ningún dato de otra persona o de otro gimnasio es alcanzable desde aquí.
 * Los datos de identidad son solo de lectura (RN-34); el teléfono, la EPS y los contactos de
 * emergencia se editan en la afiliación del establecimiento activo.
 */
@Service
public class ProfileService {

    private static final Pattern PHONE = Pattern.compile("[0-9+() -]{7,30}");
    /** Tope técnico de contactos por afiliación, para que una solicitud no pueda inflar la tabla. */
    private static final int MAX_CONTACTS = 10;

    private final JdbcClient jdbc;

    ProfileService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Identity(String documentType, String documentNumber, String firstName, String lastName,
                           LocalDate birthDate, String email) { }

    public record Contact(long id, String fullName, String relationship, String phone, boolean primary) { }

    public record Affiliation(long organizationId, String organizationName, List<String> roles, String phone,
                              Long epsId, String epsName, List<Contact> emergencyContacts) { }

    /** Identidad del usuario y su afiliación en el establecimiento activo (nula para el master, D-13). */
    public record Profile(Identity identity, Affiliation affiliation) { }

    private record Row(long memberId, String organizationName, String phone, Long epsId, String epsName) { }

    private record NewContact(String fullName, String relationship, String phone, boolean primary) {
        NewContact asPrimary() {
            return new NewContact(fullName, relationship, phone, true);
        }
    }

    public Profile get(long userId) {
        Identity identity = jdbc.sql("""
                SELECT document_type::text AS document_type, document_number, first_name, last_name,
                       birth_date, email
                FROM users WHERE id = :id
                """)
            .param("id", userId)
            .query((rs, n) -> new Identity(rs.getString("document_type"), rs.getString("document_number"),
                rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("birth_date", LocalDate.class), rs.getString("email")))
            .single();
        Affiliation affiliation = activeOrganization().flatMap(org -> affiliation(userId, org)).orElse(null);
        return new Profile(identity, affiliation);
    }

    /**
     * Cambia solo lo que viene en el cuerpo (teléfono, EPS, contactos de emergencia). Todo se valida
     * antes de escribir y se guarda en una sola transacción.
     *
     * @throws IllegalArgumentException (400) si un dato no es válido, incluido dejar la EPS o los contactos vacíos (RN-25)
     * @throws AccessDeniedException (403) si no hay un establecimiento activo con afiliación vigente
     */
    @Transactional
    public Profile update(long userId, Map<String, Object> body) {
        long orgId = ActiveOrganization.id();
        long memberId = jdbc.sql("""
                SELECT id FROM organization_members
                WHERE user_id = :user AND organization_id = :org AND is_active
                """)
            .param("user", userId).param("org", orgId)
            .query(Long.class).optional()
            .orElseThrow(() -> new AccessDeniedException("Sin afiliación activa"));
        if (body.isEmpty()) {
            throw new IllegalArgumentException("No hay nada que actualizar");
        }
        String phone = body.containsKey("phone") ? phone(body.get("phone"), "El teléfono") : null;
        Long epsId = body.containsKey("epsId") ? eps(body.get("epsId")) : null;
        List<NewContact> contacts = body.containsKey("emergencyContacts")
            ? contacts(body.get("emergencyContacts")) : null;

        if (phone != null) {
            jdbc.sql("UPDATE organization_members SET phone = :v WHERE id = :id AND organization_id = :org AND user_id = :user")
                .param("v", phone).param("id", memberId).param("org", orgId).param("user", userId).update();
        }
        if (epsId != null) {
            jdbc.sql("UPDATE organization_members SET eps_id = :v WHERE id = :id AND organization_id = :org AND user_id = :user")
                .param("v", epsId).param("id", memberId).param("org", orgId).param("user", userId).update();
        }
        if (contacts != null) {
            jdbc.sql("DELETE FROM emergency_contacts WHERE member_id = :m AND organization_id = :org")
                .param("m", memberId).param("org", orgId).update();
            for (NewContact c : contacts) {
                jdbc.sql("""
                        INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
                        VALUES (:org, :m, :name, :rel, :phone, :primary)
                        """)
                    .param("org", orgId).param("m", memberId).param("name", c.fullName())
                    .param("rel", c.relationship()).param("phone", c.phone()).param("primary", c.primary())
                    .update();
            }
        }
        return get(userId);
    }

    private static Optional<Long> activeOrganization() {
        try {
            return Optional.of(ActiveOrganization.id());
        } catch (AccessDeniedException e) {
            return Optional.empty();
        }
    }

    private Optional<Affiliation> affiliation(long userId, long orgId) {
        Optional<Row> row = jdbc.sql("""
                SELECT om.id, o.name AS org_name, om.phone, om.eps_id, e.name AS eps_name
                FROM organization_members om
                JOIN organizations o ON o.id = om.organization_id
                LEFT JOIN health_insurance_providers e ON e.id = om.eps_id
                WHERE om.user_id = :user AND om.organization_id = :org AND om.is_active
                """)
            .param("user", userId).param("org", orgId)
            .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("org_name"), rs.getString("phone"),
                rs.getObject("eps_id", Long.class), rs.getString("eps_name")))
            .optional();
        return row.map(r -> new Affiliation(orgId, r.organizationName(), roles(r.memberId()), r.phone(),
            r.epsId(), r.epsName(), contacts(r.memberId(), orgId)));
    }

    private List<String> roles(long memberId) {
        return jdbc.sql("SELECT role::text FROM member_roles WHERE member_id = :m ORDER BY role::text")
            .param("m", memberId).query(String.class).list();
    }

    private List<Contact> contacts(long memberId, long orgId) {
        return jdbc.sql("""
                SELECT id, full_name, relationship, phone, is_primary FROM emergency_contacts
                WHERE member_id = :m AND organization_id = :org ORDER BY id
                """)
            .param("m", memberId).param("org", orgId)
            .query((rs, n) -> new Contact(rs.getLong("id"), rs.getString("full_name"),
                rs.getString("relationship"), rs.getString("phone"), rs.getBoolean("is_primary")))
            .list();
    }

    private static String phone(Object value, String field) {
        if (!(value instanceof String s) || !PHONE.matcher(s.trim()).matches()) {
            throw new IllegalArgumentException(field
                + " debe tener entre 7 y 30 caracteres: dígitos, +, espacios, paréntesis o guiones");
        }
        return s.trim();
    }

    private Long eps(Object value) {
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException("La EPS es obligatoria y no puede quedar vacía (RN-25)");
        }
        long id = ((Number) value).longValue();
        boolean valid = jdbc.sql("SELECT EXISTS(SELECT 1 FROM health_insurance_providers WHERE id = :id AND is_active)")
            .param("id", id).query((rs, n) -> rs.getBoolean(1)).single();
        if (!valid) {
            throw new IllegalArgumentException("La EPS elegida no existe o no está activa");
        }
        return id;
    }

    private static List<NewContact> contacts(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException("Debe quedar al menos un contacto de emergencia (RN-25)");
        }
        if (items.size() > MAX_CONTACTS) {
            throw new IllegalArgumentException("Se admiten hasta " + MAX_CONTACTS + " contactos de emergencia");
        }
        List<NewContact> result = new ArrayList<>();
        int primaries = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("Cada contacto de emergencia debe ser un objeto");
            }
            boolean primary = Boolean.TRUE.equals(m.get("primary"));
            primaries += primary ? 1 : 0;
            result.add(new NewContact(text(m.get("fullName"), 150, "nombre"), text(m.get("relationship"), 50, "parentesco"),
                phone(m.get("phone"), "El teléfono del contacto"), primary));
        }
        if (primaries > 1) {
            throw new IllegalArgumentException("Solo un contacto puede ser el principal");
        }
        if (primaries == 0) {
            result.set(0, result.get(0).asPrimary());
        }
        return result;
    }

    private static String text(Object value, int max, String field) {
        if (!(value instanceof String s) || s.isBlank() || s.trim().length() > max) {
            throw new IllegalArgumentException("El " + field + " del contacto es obligatorio y admite hasta "
                + max + " caracteres");
        }
        return s.trim();
    }
}

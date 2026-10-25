package com.nexfit.organization;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creación de establecimientos (US-06, NEX-29). */
@Service
class EstablishmentService {

    private final JdbcClient jdbc;

    EstablishmentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Establishment(long id, String name, String contactEmail, String timezone, boolean active) { }

    /** Inserta el establecimiento activo y sin Administrador (se asigna en otra historia). */
    @Transactional
    public Establishment create(String name, String contactEmail, String timezone) {
        try {
            return jdbc.sql("""
                    INSERT INTO organizations (name, contact_email, timezone, is_active)
                    VALUES (:name, :email, :tz, true)
                    RETURNING id, name, contact_email, timezone, is_active
                    """)
                .param("name", name).param("email", contactEmail).param("tz", timezone)
                .query((rs, n) -> new Establishment(rs.getLong("id"), rs.getString("name"),
                    rs.getString("contact_email"), rs.getString("timezone"), rs.getBoolean("is_active")))
                .single();
        } catch (DataIntegrityViolationException e) {
            // check_violation del trigger organizations_timezone_valid: pg_timezone_names no es idéntico a ZoneId.
            throw new InvalidTimezoneException();
        }
    }
}

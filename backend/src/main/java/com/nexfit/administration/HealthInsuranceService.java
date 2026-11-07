package com.nexfit.administration;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catálogo de EPS (solo lectura; la gestión es US-18, NEX-30). */
@Service
class HealthInsuranceService {

    private final JdbcClient jdbc;

    HealthInsuranceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Provider(long id, String name) { }

    /** EPS activas, por nombre; las inactivas no se ofrecen para elegir. */
    @Transactional(readOnly = true)
    public List<Provider> listActive() {
        return jdbc.sql("SELECT id, name FROM health_insurance_providers WHERE is_active ORDER BY lower(name), id")
            .query((rs, n) -> new Provider(rs.getLong("id"), rs.getString("name")))
            .list();
    }
}

package com.nexfit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Datos de prueba para las pruebas de autorización: usuarios, gimnasios, afiliaciones, sedes y sesiones. */
public class AccessFixtures {

    static final String PASSWORD = "Clave-Segura-123";
    private static final AtomicInteger SEQ = new AtomicInteger();

    public record Account(long id, String email) { }

    /** Gimnasio con su Administrador (afiliación y cuenta activas). */
    public record Org(long id, Account admin, long adminMemberId) { }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final MockMvc mvc;

    public AccessFixtures(JdbcTemplate jdbc, PasswordEncoder encoder, MockMvc mvc) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mvc = mvc;
    }

    public Account user(boolean platformAdmin, boolean active) {
        int n = SEQ.incrementAndGet();
        String email = "u" + n + "@nexfit.test";
        long id = jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', ?, 'Ana', 'Pérez', DATE '1990-01-01', ?, ?, ?, ?) RETURNING id
            """, Long.class, "D" + n, email, encoder.encode(PASSWORD), platformAdmin, active);
        return new Account(id, email);
    }

    public long org(boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES ('Gym', 'g@nexfit.test', 'America/Bogota', ?) RETURNING id
            """, Long.class, active);
    }

    /** Afilia al usuario con un rol; devuelve el id de organization_members. */
    public long member(Account user, long orgId, String role, boolean active) {
        if (role.equals("client")) {
            // RN-25: el cliente exige EPS y un contacto de emergencia; todo en una sola sentencia (un commit).
            return jdbc.queryForObject("""
                WITH eps AS (INSERT INTO health_insurance_providers (name, is_active) VALUES ('EPS', true) RETURNING id),
                     m AS (INSERT INTO organization_members (organization_id, user_id, eps_id, is_active)
                           SELECT ?, ?, eps.id, ? FROM eps RETURNING id),
                     c AS (INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
                           SELECT ?, m.id, 'Luis Pérez', 'Padre', '3000000000', true FROM m),
                     r AS (INSERT INTO member_roles (member_id, role, organization_id)
                           SELECT m.id, 'client', ? FROM m)
                SELECT id FROM m
                """, Long.class, orgId, user.id(), active, orgId, orgId);
        }
        long memberId = jdbc.queryForObject("""
            INSERT INTO organization_members (organization_id, user_id, is_active)
            VALUES (?, ?, ?) RETURNING id
            """, Long.class, orgId, user.id(), active);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST(? AS member_role), ?)",
            memberId, role, orgId);
        return memberId;
    }

    public Org orgWithAdmin() {
        long orgId = org(true);
        Account admin = user(false, true);
        return new Org(orgId, admin, member(admin, orgId, "admin", true));
    }

    /** Usuario nuevo afiliado al gimnasio con un solo rol. */
    public Account actor(long orgId, String role) {
        Account a = user(false, true);
        member(a, orgId, role, true);
        return a;
    }

    public long branch(long orgId) {
        return jdbc.queryForObject("""
            INSERT INTO branches (organization_id, name, address_line1, city, department, country, is_active)
            VALUES (?, ?, 'Calle 1', 'Tunja', 'Boyacá', 'Colombia', true) RETURNING id
            """, Long.class, orgId, "Sede " + SEQ.incrementAndGet());
    }

    public int branchCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM branches WHERE organization_id = ?", Integer.class, orgId);
    }

    public Cookie login(Account account) throws Exception {
        MvcResult r = mvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(account.email(), PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getCookie("NEXFIT_SESSION");
    }
}

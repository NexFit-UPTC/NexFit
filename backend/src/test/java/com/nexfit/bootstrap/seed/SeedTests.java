package com.nexfit.bootstrap.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.bootstrap.seed.SeedService.Result;
import com.nexfit.bootstrap.seed.SeedService.Size;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** NEX-18 (TT-07): datos de prueba ficticios y conjunto de referencia de carga. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SeedTests {

    private static final String PASSWORD = "Clave-De-Prueba-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    SeedService seed;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    MockMvc mvc;

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---- criterio 1: conjunto funcional ----------------------------------------------------

    // Dos gimnasios con una sede y usuarios Master, Administrador, Staff, Entrenador y Cliente.
    @Test
    void elConjuntoFuncionalTieneDosGimnasiosConUnaSedeYUsuariosDeCadaRol() {
        seed.loadFunctional(PASSWORD);

        List<Long> orgs = jdbc.queryForList(
            "SELECT id FROM organizations WHERE name LIKE 'Gimnasio Demo %' ORDER BY id", Long.class);
        assertThat(orgs).hasSize(2);
        for (long org : orgs) {
            assertThat(count("SELECT count(*) FROM branches WHERE organization_id = ?", org)).isEqualTo(1);
            List<String> roles = jdbc.queryForList(
                "SELECT DISTINCT CAST(role AS text) FROM member_roles WHERE organization_id = ?", String.class, org);
            assertThat(roles).containsExactlyInAnyOrder("admin", "staff", "trainer", "client");
        }
        assertThat(count("SELECT count(*) FROM users WHERE is_platform_admin AND email = 'master@nexfit.test'"))
            .isEqualTo(1);
    }

    // Una persona afiliada a ambos gimnasios.
    @Test
    void hayUnaPersonaAfiliadaAAmbosGimnasios() {
        seed.loadFunctional(PASSWORD);

        Map<String, Object> shared = jdbc.queryForMap("""
            SELECT count(DISTINCT m.organization_id) AS gyms, count(*) AS members
              FROM organization_members m JOIN users u ON u.id = m.user_id
             WHERE u.email = 'cliente.compartido@nexfit.test'
            """);
        assertThat(shared).containsEntry("gyms", 2L).containsEntry("members", 2L);
    }

    // Cada cliente tiene EPS y contacto (RN-25), y los clientes con membresía vigente tienen ingresos.
    @Test
    void losClientesTienenEpsContactoMembresiaYPagoCompletado() {
        seed.loadFunctional(PASSWORD);

        assertThat(count("""
            SELECT count(*) FROM member_roles r JOIN organization_members m ON m.id = r.member_id
             WHERE r.role = 'client' AND (m.eps_id IS NULL
                OR NOT EXISTS (SELECT 1 FROM emergency_contacts e WHERE e.member_id = m.id))
            """)).isZero();
        assertThat(count("SELECT count(*) FROM memberships WHERE status = 'active'")).isGreaterThanOrEqualTo(8);
        assertThat(count("SELECT count(*) FROM payments WHERE status <> 'completed'")).isZero();
        assertThat(count("SELECT count(*) FROM check_ins")).isGreaterThan(0);
    }

    // Se puede iniciar sesión con cada rol del conjunto funcional.
    @Test
    void cadaRolDelConjuntoFuncionalPuedeIniciarSesion() throws Exception {
        seed.loadFunctional(PASSWORD);

        for (String email : List.of("master@nexfit.test", "admin.norte@nexfit.test", "staff.norte@nexfit.test",
                "trainer.norte@nexfit.test", "cliente1.norte@nexfit.test", "cliente.compartido@nexfit.test")) {
            int status = mvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andReturn().getResponse().getStatus();
            assertThat(status).as(email).isEqualTo(200);
        }
    }

    // Cargarlo de nuevo no duplica nada.
    @Test
    void cargarElConjuntoFuncionalDosVecesNoDuplicaDatos() {
        seed.loadFunctional(PASSWORD);
        int users = count("SELECT count(*) FROM users");
        int orgs = count("SELECT count(*) FROM organizations");
        int checkIns = count("SELECT count(*) FROM check_ins");

        Result again = seed.loadFunctional(PASSWORD);

        assertThat(again.skipped()).isTrue();
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(users);
        assertThat(count("SELECT count(*) FROM organizations")).isEqualTo(orgs);
        assertThat(count("SELECT count(*) FROM check_ins")).isEqualTo(checkIns);
    }

    // ---- criterio 3: todo es ficticio --------------------------------------------------------

    @Test
    void ningunDatoCorrespondeAPersonasReales() {
        seed.loadFunctional(PASSWORD);
        seed.loadReference(PASSWORD, new Size(2, 40, 150));

        assertThat(count("SELECT count(*) FROM users WHERE email NOT LIKE '%@nexfit.test'")).isZero();
        assertThat(count("SELECT count(*) FROM users WHERE document_number NOT LIKE 'TEST-%'")).isZero();
        assertThat(count("SELECT count(*) FROM organizations WHERE contact_email NOT LIKE '%@nexfit.test'")).isZero();
        // La contraseña no se guarda en claro: solo el hash.
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = 'master@nexfit.test'",
            String.class);
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
    }

    // ---- criterio 2: conjunto de referencia ---------------------------------------------------

    // Las cifras por defecto son las de la issue: 5 gimnasios, 1.000 clientes y 20.000 ingresos por gimnasio.
    @Test
    void lasCifrasPorDefectoDelConjuntoDeReferenciaSonLasDeLaIssue() {
        assertThat(Size.REFERENCE_DEFAULT).isEqualTo(new Size(5, 1_000, 20_000));
    }

    // El conjunto de referencia (a escala reducida) crea la cantidad pedida por gimnasio.
    @Test
    void elConjuntoDeReferenciaCreaLaCantidadPedidaPorGimnasio() {
        Result first = seed.loadReference(PASSWORD, new Size(2, 40, 150));

        List<Long> orgs = jdbc.queryForList(
            "SELECT id FROM organizations WHERE name LIKE 'Gimnasio Carga %' ORDER BY id", Long.class);
        if (!first.skipped()) {
            assertThat(first.gyms()).isEqualTo(2);
            assertThat(first.clients()).isEqualTo(80);
            assertThat(first.checkIns()).isEqualTo(300);
        }
        assertThat(orgs).hasSizeGreaterThanOrEqualTo(2);
        for (long org : orgs.subList(0, 2)) {
            assertThat(count("SELECT count(*) FROM member_roles WHERE organization_id = ? AND role = 'client'", org))
                .isEqualTo(40);
            assertThat(count("SELECT count(*) FROM check_ins WHERE organization_id = ?", org)).isEqualTo(150);
            assertThat(count("SELECT count(*) FROM memberships WHERE organization_id = ?", org)).isEqualTo(40);
            assertThat(count("SELECT count(*) FROM payments WHERE organization_id = ?", org)).isEqualTo(40);
            // Los ingresos se reparten entre todos los clientes.
            assertThat(count("SELECT count(DISTINCT member_id) FROM check_ins WHERE organization_id = ?", org))
                .isEqualTo(40);
            // Aislamiento: ningún ingreso apunta a un miembro de otro gimnasio.
            assertThat(count("""
                SELECT count(*) FROM check_ins c JOIN organization_members m ON m.id = c.member_id
                 WHERE c.organization_id = ? AND m.organization_id <> c.organization_id
                """, org)).isZero();
        }
    }

    // ---- guarda contra bases remotas -----------------------------------------------------------

    @Test
    void soloLasBasesLocalesSeConsideranSeguras() {
        assertThat(SeedRunner.isLocal("jdbc:postgresql://localhost:5432/nexfit")).isTrue();
        assertThat(SeedRunner.isLocal("jdbc:postgresql://127.0.0.1:5432/nexfit")).isTrue();
        assertThat(SeedRunner.isLocal("jdbc:postgresql://ep-cool-123.us-east-2.aws.neon.tech/nexfit?sslmode=require"))
            .isFalse();
        assertThat(SeedRunner.isLocal("")).isFalse();
        assertThat(SeedRunner.isLocal(null)).isFalse();
    }
}

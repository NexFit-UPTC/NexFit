package com.nexfit.bootstrap.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Conjunto {@code massive} a escala {@code small}: coherencia con el MER, las RN y la idempotencia. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class MassiveSeedTests {

    private static final String PASSWORD = "Clave-De-Prueba-123";
    private static final String MASSIVE_ORGS =
        "(SELECT id FROM organizations WHERE contact_email LIKE '%@massive.nexfit.test')";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MassiveSeedService seed;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    private static MassiveSeedService.Result first;

    private MassiveSeedService.Result loaded() {
        if (first == null) {
            first = seed.load(PASSWORD, MassiveScale.SMALL, null);
        }
        return first;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void cargaLaEscalaSmallConSusConteos() {
        MassiveSeedService.Result result = loaded();

        assertThat(result.gymsLoaded()).isEqualTo(MassiveScale.SMALL.gyms);
        assertThat(result.counts().get("organizations")).isEqualTo(3);
        assertThat(result.counts().get("branches")).isEqualTo(6);
        assertThat(result.counts().get("users")).isBetween(1_450L, 1_550L);
        assertThat(result.counts().get("check_ins")).isBetween(38_000L, 42_000L);
        assertThat(result.counts().get("classes")).isPositive();
        assertThat(result.counts().get("equipment")).isPositive();
    }

    @Test
    void cargarDeNuevoNoDuplicaNiSobrescribe() {
        loaded();
        long users = count("SELECT count(*) FROM users");
        long checkIns = count("SELECT count(*) FROM check_ins");

        MassiveSeedService.Result again = seed.load(PASSWORD, MassiveScale.SMALL, null);

        assertThat(again.gymsLoaded()).isZero();
        assertThat(again.gymsSkipped()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(users);
        assertThat(count("SELECT count(*) FROM check_ins")).isEqualTo(checkIns);
    }

    // RN-25: cada cliente tiene EPS y al menos un contacto de emergencia.
    @Test
    void cadaClienteTieneEpsDeLaListaConfirmadaYContacto() {
        loaded();

        assertThat(count("""
            SELECT count(*) FROM member_roles r JOIN organization_members m ON m.id = r.member_id
             WHERE r.role = 'client' AND (m.eps_id IS NULL
                OR NOT EXISTS (SELECT 1 FROM emergency_contacts e WHERE e.member_id = m.id))
            """)).isZero();
        List<String> epsNames = jdbc.queryForList("""
            SELECT DISTINCT p.name FROM organization_members m
              JOIN health_insurance_providers p ON p.id = m.eps_id
             WHERE m.organization_id IN %s
            """.formatted(MASSIVE_ORGS), String.class);
        assertThat(epsNames).isNotEmpty().doesNotContain("Compensar", "Comfenalco Valle", "Anas Wayuu",
            "Pijaos Salud", "Mallamas");
    }

    // Los ingresos caen siempre dentro de una membresía activa (el trigger estuvo apagado durante la carga).
    @Test
    void ningunIngresoQuedaFueraDeLaVigenciaYElTriggerQuedaActivo() {
        loaded();

        assertThat(count("""
            SELECT count(*) FROM check_ins c JOIN organizations o ON o.id = c.organization_id
             WHERE c.organization_id IN %s AND NOT EXISTS (
                SELECT 1 FROM memberships m
                 WHERE m.member_id = c.member_id AND m.status = 'active'
                   AND (c.check_in_at AT TIME ZONE o.timezone)::date BETWEEN m.start_date AND m.end_date)
            """.formatted(MASSIVE_ORGS))).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT tgenabled FROM pg_trigger WHERE tgname = 'check_ins_validate'", String.class)).isEqualTo("O");
    }

    // Aislamiento: nada referencia a un miembro de otro gimnasio.
    @Test
    void todoDatoPerteneceALaOrganizacionDeSuMiembro() {
        loaded();

        for (String table : List.of("check_ins", "memberships", "payments", "class_bookings", "emergency_contacts")) {
            assertThat(count("SELECT count(*) FROM " + table + " t JOIN organization_members m ON m.id = t.member_id "
                + "WHERE t.organization_id <> m.organization_id")).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM classes c JOIN branches b ON b.id = c.branch_id "
            + "WHERE c.organization_id <> b.organization_id")).isZero();
    }

    @Test
    void documentosNumericosYCorreosConDominiosComunes() {
        loaded();

        assertThat(count("""
            SELECT count(*) FROM users u JOIN organization_members m ON m.user_id = u.id
             WHERE m.organization_id IN %s AND u.email NOT LIKE '%%@nexfit.test'
               AND (u.document_number !~ '^[0-9]{10}$'
                    OR u.email !~ '@(gmail\\.com|hotmail\\.com|outlook\\.com)$' OR u.email !~ '[0-9]@')
            """.formatted(MASSIVE_ORGS))).isZero();
    }

    @Test
    void cadaUsuarioDeDemoPuedeIniciarSesion() throws Exception {
        loaded();

        for (String handle : List.of("master", "admin", "staff", "trainer", "cliente")) {
            int status = mvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"massive.%s@nexfit.test\",\"password\":\"%s\"}".formatted(handle, PASSWORD)))
                .andReturn().getResponse().getStatus();
            assertThat(status).as(handle).isEqualTo(200);
        }
    }

    @Test
    void elArchivoDelEquipoAfiliaCuentasSinDuplicarlas() throws Exception {
        loaded();
        Path file = Files.createTempFile("equipo", ".csv");
        try {
            Files.writeString(file, "# rol,correo,tipoDoc,numeroDoc,nombres,apellidos,nacimiento,telefono\n"
                + "client,integrante.uno@example.test,CC,1999999991,Integrante,Uno,2000-01-15,3001112233\n"
                + "trainer,integrante.dos@example.test,CC,1999999992,Integrante,Dos,1998-03-02,3001112244\n");

            seed.load(PASSWORD, MassiveScale.SMALL, file);
            seed.load(PASSWORD, MassiveScale.SMALL, file);

            assertThat(count("SELECT count(*) FROM users WHERE email LIKE 'integrante.%@example.test'")).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM trainer_profiles t JOIN organization_members m "
                + "ON m.id = t.member_id JOIN users u ON u.id = m.user_id "
                + "WHERE u.email = 'integrante.dos@example.test'")).isEqualTo(1);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}

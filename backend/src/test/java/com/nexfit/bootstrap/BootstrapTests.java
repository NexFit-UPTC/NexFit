package com.nexfit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** NEX-15 · TT-06: inicialización controlada del Master, catálogo de EPS y ejercicios globales. */
@Testcontainers
@SpringBootTest
class BootstrapTests {

    private static final String PASSWORD = "Clave-Temporal-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired BootstrapService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ApplicationContext context;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void baseRecienMigrada() {
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM exercises WHERE organization_id IS NULL");
        jdbc.update("DELETE FROM health_insurance_providers");
    }

    private static MasterInput input() {
        return new MasterInput("master@ejemplo.test", "CC", "MASTER-0001", "Administrador", "Master",
            LocalDate.of(1970, 1, 1), PASSWORD);
    }

    @Test
    void creaElMasterActivoSinAfiliacionYConContrasenaTemporal() {
        BootstrapService.Result result = service.initialize(BootstrapTests::input);

        assertThat(result.masterCreated()).isTrue();
        Map<String, Object> master = jdbc.queryForMap(
            "SELECT is_platform_admin, is_active, must_change_password FROM users WHERE is_platform_admin");
        assertThat(master).containsEntry("is_active", true).containsEntry("must_change_password", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_members", Integer.class)).isZero();
    }

    @Test
    void laContrasenaSoloExisteComoHash() {
        service.initialize(BootstrapTests::input);

        String hash = jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE is_platform_admin", String.class);
        String fila = jdbc.queryForObject("SELECT u::text FROM users u WHERE is_platform_admin", String.class);

        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        assertThat(fila).doesNotContain(PASSWORD);
    }

    @Test
    void rechazaUnaContrasenaTemporalDemasiadoCortaYNoCreaNada() {
        MasterInput corta = new MasterInput("master@ejemplo.test", "CC", "MASTER-0001", "Administrador", "Master",
            LocalDate.of(1970, 1, 1), "corta");

        assertThatThrownBy(() -> service.initialize(() -> corta))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("al menos");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM health_insurance_providers", Integer.class)).isZero();
    }

    @Test
    void noConvierteEnMasterUnaCuentaQueYaExisteConEseCorreo() {
        jdbc.update("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', '1000', 'Ana', 'Pérez', DATE '1990-01-01', 'MASTER@ejemplo.test', 'x', false, true)
            """);

        assertThatThrownBy(() -> service.initialize(BootstrapTests::input))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE is_platform_admin", Integer.class)).isZero();
    }

    @Test
    void elCatalogoDeEpsQuedaCargadoYActivo() {
        service.initialize(BootstrapTests::input);

        List<String> cargadas = jdbc.queryForList(
            "SELECT name FROM health_insurance_providers WHERE is_active", String.class);
        assertThat(cargadas).containsExactlyInAnyOrderElementsOf(BootstrapCatalogs.epsNames());
    }

    @Test
    void losEjerciciosGlobalesQuedanSinOrganizacionYActivos() {
        service.initialize(BootstrapTests::input);

        List<String> cargados = jdbc.queryForList(
            "SELECT name FROM exercises WHERE organization_id IS NULL AND is_active", String.class);
        assertThat(cargados).containsExactlyInAnyOrderElementsOf(
            BootstrapCatalogs.globalExercises().stream().map(BootstrapCatalogs.Exercise::name).toList());
    }

    @Test
    void ejecutarloDosVecesNoDuplicaNiCambiaLaContrasenaDelMaster() {
        service.initialize(BootstrapTests::input);
        String hashAntes = jdbc.queryForObject("SELECT password_hash FROM users WHERE is_platform_admin", String.class);
        int usuarios = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        int eps = jdbc.queryForObject("SELECT count(*) FROM health_insurance_providers", Integer.class);
        int ejercicios = jdbc.queryForObject("SELECT count(*) FROM exercises", Integer.class);

        BootstrapService.Result segunda = service.initialize(() -> {
            throw new AssertionError("En la segunda ejecución no se debe pedir ninguna contraseña");
        });

        assertThat(segunda).isEqualTo(new BootstrapService.Result(false, 0, 0));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE is_platform_admin", String.class))
            .isEqualTo(hashAntes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(usuarios);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM health_insurance_providers", Integer.class)).isEqualTo(eps);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercises", Integer.class)).isEqualTo(ejercicios);
    }

    @Test
    void noReactivaUnaEpsQueElMasterDesactivo() {
        service.initialize(BootstrapTests::input);
        jdbc.update("UPDATE health_insurance_providers SET is_active = false WHERE name = 'Nueva EPS'");

        service.initialize(BootstrapTests::input);

        assertThat(jdbc.queryForObject(
            "SELECT is_active FROM health_insurance_providers WHERE name = 'Nueva EPS'", Boolean.class)).isFalse();
    }

    @Test
    void noExisteNingunEndpointNiElProcedimientoEstaActivoEnLaApi() {
        List<String> rutas = handlerMapping.getHandlerMethods().keySet().stream()
            .map(info -> info.toString().toLowerCase(Locale.ROOT)).toList();

        assertThat(rutas).noneMatch(r -> r.contains("master") || r.contains("bootstrap"));
        assertThat(context.getBeansOfType(BootstrapRunner.class)).isEmpty();
    }
}

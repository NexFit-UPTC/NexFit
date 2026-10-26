package com.nexfit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** NEX-25 (TT-01): autorización por rol y establecimiento en el servidor. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccessControlTests.Probe.class)
class AccessControlTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    /** Endpoints solo de prueba, con reglas de la matriz 3.7 en distintas funciones. */
    @TestConfiguration
    static class Probe {
        @RestController
        @RequestMapping("/test/access")
        static class ProbeController {
            private final JdbcTemplate jdbc;

            ProbeController(JdbcTemplate jdbc) {
                this.jdbc = jdbc;
            }

            /** Sede por id, filtrada por el establecimiento activo; si no es de este gimnasio, 404. */
            @RequiresAccess(funcion = Funcion.SEDES, accion = Accion.CONSULTAR)
            @GetMapping("/branches/{id}")
            Map<String, Object> branch(@PathVariable long id) {
                return jdbc.queryForList("SELECT id, name FROM branches WHERE id = ? AND organization_id = ?",
                        id, ActiveOrganization.id()).stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            }

            /** Escritura de prueba: crea una sede en el establecimiento activo. */
            @RequiresAccess(funcion = Funcion.SEDES, accion = Accion.CREAR)
            @PostMapping("/branches")
            ResponseEntity<Void> createBranch() {
                jdbc.update("""
                    INSERT INTO branches (organization_id, name, address_line1, city, department, country, is_active)
                    VALUES (?, 'Nueva', 'Calle 1', 'Tunja', 'Boyacá', 'Colombia', true)
                    """, ActiveOrganization.id());
                return ResponseEntity.status(HttpStatus.CREATED).build();
            }

            @RequiresAccess(funcion = Funcion.CLIENTES, accion = Accion.CONSULTAR)
            @GetMapping("/clients")
            String clients() {
                return "ok";
            }

            @RequiresAccess(funcion = Funcion.RUTINAS, accion = Accion.CONSULTAR)
            @GetMapping("/routines")
            String routines() {
                return "ok";
            }

            @RequiresAccess(funcion = Funcion.INDICADORES, accion = Accion.CONSULTAR_AGREGADO)
            @GetMapping("/aggregate")
            String aggregate() {
                return "ok";
            }
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    AccessFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new AccessFixtures(jdbc, encoder, mvc);
    }

    private int status(String path, Cookie cookie) throws Exception {
        return mvc.perform(get(path).cookie(cookie)).andReturn().getResponse().getStatus();
    }

    private MvcResult call(String path, Cookie cookie) throws Exception {
        return mvc.perform(get(path).cookie(cookie)).andReturn();
    }

    private String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    // Criterio 1: sin sesión válida, 401 (con token CSRF, para que el rechazo sea por falta de sesión).
    @Test
    void sinSesionDa401EnLecturaYEscrituraAunConTokenCsrf() throws Exception {
        assertThat(mvc.perform(get("/test/access/clients")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/test/access/branches").with(csrf())).andReturn().getResponse().getStatus())
            .isEqualTo(401);
    }

    // Criterio 1: sin permiso, 403 y ningún cambio; con permiso, cambia.
    @Test
    @ReglaDeNegocio("RN-06")
    void sinPermisoDa403SinCambiosEnBdYConPermisoCambia() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        int before = fx.branchCount(org.id());

        MvcResult denied = mvc.perform(post("/test/access/branches").with(csrf()).cookie(staff)).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(denied)).contains("ACCESS_DENIED");
        assertThat(fx.branchCount(org.id())).isEqualTo(before);

        Cookie admin = fx.login(org.admin());
        assertThat(mvc.perform(post("/test/access/branches").with(csrf()).cookie(admin))
            .andReturn().getResponse().getStatus()).isEqualTo(201);
        assertThat(fx.branchCount(org.id())).isEqualTo(before + 1);
    }

    // Criterio 2: recurso de otro gimnasio por id → 404 idéntico al de un id inexistente.
    @Test
    @ReglaDeNegocio("RN-03")
    void sedeDeOtroGimnasioDa404IdenticoAUnIdInexistente() throws Exception {
        Org a = fx.orgWithAdmin();
        Org b = fx.orgWithAdmin();
        long ownBranch = fx.branch(a.id());
        long otherBranch = fx.branch(b.id());
        Cookie adminA = fx.login(a.admin());

        assertThat(status("/test/access/branches/" + ownBranch, adminA)).isEqualTo(200);

        MvcResult crossTenant = call("/test/access/branches/" + otherBranch, adminA);
        MvcResult missing = call("/test/access/branches/" + (otherBranch + 100_000), adminA);
        assertThat(crossTenant.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(crossTenant)).isEqualTo(body(missing));
    }

    // RN-08: el master no accede a datos de clientes; solo a agregados.
    @Test
    @ReglaDeNegocio("RN-08")
    void elMasterEsDenegadoEnDatosDeClientesYSoloVeAgregados() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie master = fx.login(fx.user(true, true));
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        MvcResult denied = call("/test/access/clients", master);
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(denied)).contains("ACCESS_DENIED");
        assertThat(status("/test/access/clients", staff)).isEqualTo(200);
        assertThat(status("/test/access/aggregate", master)).isEqualTo(200);
        assertThat(status("/test/access/aggregate", staff)).isEqualTo(403);
    }

    // RN-24: las rutinas solo las ven el entrenador y el cliente.
    @Test
    @ReglaDeNegocio("RN-24")
    void enRutinasSoloEntranEntrenadorYCliente() throws Exception {
        Org org = fx.orgWithAdmin();
        assertThat(status("/test/access/routines", fx.login(fx.actor(org.id(), "trainer")))).isEqualTo(200);
        assertThat(status("/test/access/routines", fx.login(fx.actor(org.id(), "client")))).isEqualTo(200);
        assertThat(status("/test/access/routines", fx.login(fx.actor(org.id(), "staff")))).isEqualTo(403);
        assertThat(status("/test/access/routines", fx.login(org.admin()))).isEqualTo(403);
        assertThat(status("/test/access/routines", fx.login(fx.user(true, true)))).isEqualTo(403);

        // RN-03: con staff y entrenador a la vez, rige la unión: entra por el entrenador.
        Account staffYTrainer = fx.user(false, true);
        long member = fx.member(staffYTrainer, org.id(), "staff", true);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST('trainer' AS member_role), ?)",
            member, org.id());
        assertThat(status("/test/access/routines", fx.login(staffYTrainer))).isEqualTo(200);
    }

    // Criterio 7 (D-14): el cambio de rol se aplica en la siguiente solicitud con la misma cookie.
    @Test
    @ReglaDeNegocio("RN-03")
    void elCambioDeRolSeAplicaEnLaSiguienteSolicitud() throws Exception {
        Org org = fx.orgWithAdmin();
        Account user = fx.user(false, true);
        long member = fx.member(user, org.id(), "staff", true);
        Cookie cookie = fx.login(user);
        assertThat(status("/test/access/routines", cookie)).isEqualTo(403);

        jdbc.update("UPDATE member_roles SET role = 'trainer' WHERE member_id = ?", member);
        assertThat(status("/test/access/routines", cookie)).isEqualTo(200);
    }

    // Criterio 3 (RN-33): gimnasio o afiliación desactivados en sesión abierta → se rechaza en la siguiente.
    @Test
    void gimnasioDesactivadoEnSesionAbiertaDa403EstablishmentRequired() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        assertThat(status("/test/access/clients", staff)).isEqualTo(200);

        jdbc.update("UPDATE organizations SET is_active = false WHERE id = ?", org.id());
        MvcResult r = call("/test/access/clients", staff);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r)).contains("ESTABLISHMENT_REQUIRED");
    }

    @Test
    void afiliacionDesactivadaEnSesionAbiertaDa403EstablishmentRequired() throws Exception {
        Org org = fx.orgWithAdmin();
        Account staff = fx.user(false, true);
        long member = fx.member(staff, org.id(), "staff", true);
        Cookie cookie = fx.login(staff);
        assertThat(status("/test/access/clients", cookie)).isEqualTo(200);

        jdbc.update("UPDATE organization_members SET is_active = false WHERE id = ?", member);
        MvcResult r = call("/test/access/clients", cookie);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r)).contains("ESTABLISHMENT_REQUIRED");
    }

    // RN-04: sin Administrador activo no se opera; el master sí; la lista de establecimientos no se filtra.
    @Test
    void gimnasioSinAdminDa403EstablishmentWithoutAdmin() throws Exception {
        long orgId = fx.org(true);
        Cookie staff = fx.login(fx.actor(orgId, "staff"));

        MvcResult r = call("/test/access/clients", staff);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r)).contains("ESTABLISHMENT_WITHOUT_ADMIN");
        assertThat(status("/auth/establishments", staff)).isEqualTo(200);
    }

    @Test
    void gimnasioConAdminOperaYAdminInactivoCuentaComoSinAdmin() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        assertThat(status("/test/access/clients", staff)).isEqualTo(200);

        jdbc.update("UPDATE organization_members SET is_active = false WHERE id = ?", org.adminMemberId());
        assertThat(body(call("/test/access/clients", staff))).contains("ESTABLISHMENT_WITHOUT_ADMIN");

        jdbc.update("UPDATE organization_members SET is_active = true WHERE id = ?", org.adminMemberId());
        assertThat(status("/test/access/clients", staff)).isEqualTo(200);

        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", org.admin().id());
        MvcResult r = call("/test/access/clients", staff);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r)).contains("ESTABLISHMENT_WITHOUT_ADMIN");
    }

    @Test
    void elMasterOperaAunqueHayaGimnasiosSinAdmin() throws Exception {
        fx.org(true);
        Cookie master = fx.login(fx.user(true, true));
        assertThat(status("/test/access/aggregate", master)).isEqualTo(200);
    }
}

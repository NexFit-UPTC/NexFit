package com.nexfit.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** NEX-32 (US-07): consultar y modificar establecimientos. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EstablishmentTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private AccessFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new AccessFixtures(jdbc, encoder, mvc);
    }

    private MvcResult list(Cookie session) throws Exception {
        return mvc.perform(get("/establishments").cookie(session)).andReturn();
    }

    private MvcResult one(Cookie session, long id) throws Exception {
        return mvc.perform(get("/establishments/" + id).cookie(session)).andReturn();
    }

    private MvcResult update(Cookie session, long id, String json) throws Exception {
        return mvc.perform(put("/establishments/" + id).with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private long orgNamed(String name) {
        return jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES (?, 'g@nexfit.test', 'America/Bogota', true) RETURNING id
            """, Long.class, name);
    }

    private void assertRejected(MvcResult r, String field) throws Exception {
        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r)).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"" + field + "\"");
    }

    // El Master lista con conteos de sedes y clientes, ordenada por nombre.
    @Test
    void elMasterListaConConteosYOrden() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long orgId = fx.org(true);
        fx.branch(orgId);
        fx.branch(orgId);
        Account client = fx.user(false, true);
        fx.member(client, orgId, "client", true);
        long aaa = orgNamed("AAA Gym");
        long zzz = orgNamed("ZZZ Gym");

        String json = body(list(master));
        assertThat(status(list(master))).isEqualTo(200);
        assertThat(json).contains("\"id\":" + orgId, "\"branchCount\":2", "\"clientCount\":1");
        assertThat(json.indexOf("AAA Gym")).isLessThan(json.indexOf("ZZZ Gym"));
        assertThat(aaa).isPositive();
        assertThat(zzz).isPositive();
    }

    // El Master consulta uno por id, con sus conteos.
    @Test
    void elMasterConsultaUnoPorId() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long orgId = fx.org(true);
        fx.branch(orgId);

        MvcResult r = one(master, orgId);
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"id\":" + orgId, "\"branchCount\":1", "\"clientCount\":0");
        assertThat(status(one(master, 999999L))).isEqualTo(404);
    }

    // La lista es solo del Master: el Admin recibe 403.
    @Test
    void elAdminNoVeLaListaDeTodos() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());

        assertThat(status(list(admin))).isEqualTo(403);
        assertThat(status(one(admin, org.id()))).isEqualTo(403);
    }

    // Solo el Master modifica: el Admin recibe 403.
    @Test
    void soloElMasterModifica() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());

        MvcResult r = update(admin, org.id(),
            "{\"name\":\"Nuevo\",\"contactEmail\":\"n@nexfit.test\",\"timezone\":\"America/Bogota\"}");
        assertThat(status(r)).isEqualTo(403);
    }

    // El Master modifica nombre, correo y zona; el estado no cambia.
    @Test
    void elMasterModificaYElEstadoNoCambia() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long orgId = fx.org(true);

        MvcResult r = update(master, orgId,
            "{\"name\":\"  Gym Nuevo  \",\"contactEmail\":\"nuevo@nexfit.test\",\"timezone\":\"America/Mexico_City\"}");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"name\":\"Gym Nuevo\"", "\"timezone\":\"America/Mexico_City\"");
        assertThat(jdbc.queryForObject("SELECT is_active FROM organizations WHERE id = ?",
            Boolean.class, orgId)).isTrue();
        assertThat(status(update(master, 999999L,
            "{\"name\":\"X\",\"contactEmail\":\"x@nexfit.test\",\"timezone\":\"America/Bogota\"}"))).isEqualTo(404);
    }

    // Sin nombre, con correo inválido o zona inválida se rechaza indicando el campo.
    @Test
    void modificarInvalidoSeRechazaIndicandoElCampo() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long orgId = fx.org(true);

        assertRejected(update(master, orgId,
            "{\"contactEmail\":\"a@nexfit.test\",\"timezone\":\"America/Bogota\"}"), "name");
        assertRejected(update(master, orgId,
            "{\"name\":\"Gym\",\"contactEmail\":\"no-es-correo\",\"timezone\":\"America/Bogota\"}"), "contactEmail");
        assertRejected(update(master, orgId,
            "{\"name\":\"Gym\",\"contactEmail\":\"a@nexfit.test\",\"timezone\":\"No/Existe\"}"), "timezone");
        assertRejected(update(master, orgId,
            "{\"name\":\"Gym\",\"contactEmail\":\"a@nexfit.test\"}"), "timezone");
    }

    // La lista no incluye datos personales de clientes (RN-08): solo conteos.
    @Test
    @ReglaDeNegocio("RN-08")
    void laListaNoIncluyeDatosPersonalesDeClientes() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        long orgId = fx.org(true);
        Account client = fx.user(false, true);
        fx.member(client, orgId, "client", true);

        String json = body(list(master));
        assertThat(json).doesNotContain(client.email());
    }
}

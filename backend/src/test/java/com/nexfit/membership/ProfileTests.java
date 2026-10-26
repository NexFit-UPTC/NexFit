package com.nexfit.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.Accion;
import com.nexfit.shared.Funcion;
import com.nexfit.shared.Permisos;
import com.nexfit.shared.Rol;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/** NEX-27 (US-17): consultar y actualizar la información personal propia. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ProfileTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static final String PASSWORD = "Clave-Segura-123";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private record Acct(long id, String email, String doc) { }

    private record Org(long id, Acct admin) { }

    // ---------- Criterio 1: consulta de identidad y afiliación del establecimiento activo ----------

    @Test
    void consultaMuestraIdentidadYAfiliacionDelEstablecimientoActivo() throws Exception {
        Org org = orgWithAdmin();
        Acct ana = user(false);
        long eps = eps(true);
        member(ana, org.id(), "client", "3101112233", eps);
        MvcResult r = read(login(ana));
        String body = body(r);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).contains(ana.email(), ana.doc(), "3101112233", "Luis Pérez", "client")
            .contains("\"organizationId\":" + org.id());
    }

    // ---------- Criterio 2: el cambio permitido se guarda y se ve en la consulta siguiente ----------

    @Test
    void modificarElTelefonoSeGuardaYSeRefleja() throws Exception {
        Org org = orgWithAdmin();
        Acct ana = user(false);
        long memberId = member(ana, org.id(), "client", "3101112233", eps(true));
        Cookie session = login(ana);
        assertThat(edit(session, "{\"phone\":\"3207778899\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(body(read(session))).contains("3207778899").doesNotContain("3101112233");
        assertThat(phoneOf(memberId)).isEqualTo("3207778899");
    }

    @Test
    void elClienteCambiaEpsYReemplazaSusContactosSinTocarOtrosClientes() throws Exception {
        Org org = orgWithAdmin();
        Acct ana = user(false);
        Acct otro = user(false);
        long anaMember = member(ana, org.id(), "client", "3101112233", eps(true));
        long otroMember = member(otro, org.id(), "client", "3101112244", eps(true));
        long nuevaEps = eps(true);
        Cookie session = login(ana);
        String json = """
            {"epsId": %d, "emergencyContacts": [
              {"fullName": "Marta Gómez", "relationship": "Madre", "phone": "3112223344", "primary": true},
              {"fullName": "Pedro Ruiz", "relationship": "Hermano", "phone": "3123334455"}]}
            """.formatted(nuevaEps);
        assertThat(edit(session, json).getResponse().getStatus()).isEqualTo(200);
        assertThat(epsOf(anaMember)).isEqualTo(nuevaEps);
        assertThat(contactCount(anaMember)).isEqualTo(2);
        assertThat(primaryCount(anaMember)).isEqualTo(1);
        assertThat(contactCount(otroMember)).isEqualTo(1);
        assertThat(phoneOf(otroMember)).isEqualTo("3101112244");
    }

    // RN-25: la EPS y los contactos nunca quedan vacíos; un dato inválido no cambia nada.
    @Test
    void laEpsYLosContactosNoSePuedenVaciarNiUsarDatosInvalidos() throws Exception {
        Org org = orgWithAdmin();
        Acct ana = user(false);
        long epsActual = eps(true);
        long memberId = member(ana, org.id(), "client", "3101112233", epsActual);
        Cookie session = login(ana);
        long inactiva = eps(false);
        List<String> invalidos = List.of(
            "{\"epsId\":null}",
            "{\"epsId\":\"abc\"}",
            "{\"epsId\":999999}",
            "{\"epsId\":" + inactiva + "}",
            "{\"emergencyContacts\":[]}",
            "{\"emergencyContacts\":null}",
            "{\"emergencyContacts\":[{\"fullName\":\" \",\"relationship\":\"Madre\",\"phone\":\"3112223344\"}]}",
            "{\"emergencyContacts\":[{\"fullName\":\"A\",\"relationship\":\"B\",\"phone\":\"x\"}]}",
            "{\"emergencyContacts\":[{\"fullName\":\"A\",\"relationship\":\"B\",\"phone\":\"3112223344\",\"primary\":true},"
                + "{\"fullName\":\"C\",\"relationship\":\"D\",\"phone\":\"3112223355\",\"primary\":true}]}",
            "{\"phone\":\"abc\"}",
            "{}");
        for (String json : invalidos) {
            assertThat(edit(session, json).getResponse().getStatus()).as(json).isEqualTo(400);
        }
        assertThat(epsOf(memberId)).isEqualTo(epsActual);
        assertThat(contactCount(memberId)).isEqualTo(1);
        assertThat(phoneOf(memberId)).isEqualTo("3101112233");
    }

    // ---------- Criterios 3 y 4: identidad, establecimiento y estado no se pueden modificar ----------

    @Test
    @ReglaDeNegocio("RN-34")
    void identidadEstablecimientoYEstadoSeRechazanParaTodosLosRolesSinCambiarNada() throws Exception {
        Org org = orgWithAdmin();
        Org otraOrg = orgWithAdmin();
        Acct staff = user(false);
        Acct cliente = user(false);
        member(staff, org.id(), "staff", "3001230001", null);
        member(cliente, org.id(), "client", "3001230002", eps(true));
        List<String> prohibidos = List.of(
            "{\"firstName\":\"Hackeado\"}",
            "{\"lastName\":\"Hackeado\"}",
            "{\"documentNumber\":\"999\"}",
            "{\"birthDate\":\"2000-01-01\"}",
            "{\"email\":\"hack@nexfit.test\"}",
            "{\"organizationId\":" + otraOrg.id() + "}",
            "{\"isActive\":false}",
            "{\"status\":\"SUSPENDED\"}",
            "{\"roles\":[\"admin\"]}",
            "{\"phone\":\"3207778899\",\"firstName\":\"Hackeado\"}");
        for (Acct who : List.of(org.admin(), staff, cliente)) {
            Cookie session = login(who);
            for (String json : prohibidos) {
                assertThat(edit(session, json).getResponse().getStatus()).as(who.email() + " " + json).isEqualTo(403);
            }
            assertThat(jdbc.queryForObject("SELECT first_name FROM users WHERE id = ?", String.class, who.id()))
                .isNotEqualTo("Hackeado");
            assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, who.id()))
                .isEqualTo(who.email());
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM organization_members WHERE user_id = ? AND is_active", Integer.class, who.id()))
                .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM organization_members WHERE user_id = ? AND organization_id = ?",
                Integer.class, who.id(), otraOrg.id())).isZero();
        }
    }

    // Criterio 4: la matriz 3.7 tampoco le da al Cliente cambiar la afiliación ni el estado de la membresía.
    @Test
    void laMatrizNoLeDaAlClienteActivarAfiliacionNiSuspenderOCancelarMembresias() {
        assertThat(Permisos.rolesPermitidos(Funcion.CLIENTES, Accion.ACTIVAR)).doesNotContain(Rol.CLIENT);
        assertThat(Permisos.rolesPermitidos(Funcion.MEMBRESIAS, Accion.SUSPENDER)).doesNotContain(Rol.CLIENT);
        assertThat(Permisos.rolesPermitidos(Funcion.MEMBRESIAS, Accion.CANCELAR)).doesNotContain(Rol.CLIENT);
        assertThat(Permisos.rolesPermitidos(Funcion.MEMBRESIAS, Accion.MODIFICAR)).doesNotContain(Rol.CLIENT);
    }

    @Test
    void soloElClienteEditaEpsYContactos() throws Exception {
        Org org = orgWithAdmin();
        Acct entrenador = user(false);
        long memberId = member(entrenador, org.id(), "trainer", "3001230003", null);
        Cookie session = login(entrenador);
        assertThat(edit(session, "{\"epsId\":" + eps(true) + "}").getResponse().getStatus()).isEqualTo(403);
        assertThat(edit(session, "{\"emergencyContacts\":[{\"fullName\":\"A\",\"relationship\":\"B\","
            + "\"phone\":\"3112223344\"}]}").getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT eps_id FROM organization_members WHERE id = ?", Long.class, memberId))
            .isNull();
        assertThat(edit(session, "{\"phone\":\"3207778899\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(phoneOf(memberId)).isEqualTo("3207778899");
    }

    // ---------- Aislamiento: nadie ve ni toca datos de otra persona u otro gimnasio ----------

    @Test
    @ReglaDeNegocio("RN-34")
    void nadieVeNiTocaDatosDeOtraPersonaAunPidiendoloEnLaSolicitud() throws Exception {
        Org org = orgWithAdmin();
        Acct ana = user(false);
        Acct beto = user(false);
        member(ana, org.id(), "client", "3001110001", eps(true));
        long betoMember = member(beto, org.id(), "client", "3001110002", eps(true));
        Cookie session = login(ana);
        MvcResult r = mvc.perform(get("/me").param("userId", String.valueOf(beto.id()))
            .param("memberId", String.valueOf(betoMember)).cookie(session)).andReturn();
        String body = body(r);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).contains(ana.email()).doesNotContain(beto.email(), beto.doc(), "3001110002");
        assertThat(edit(session, "{\"userId\":" + beto.id() + ",\"phone\":\"3009998877\"}").getResponse().getStatus())
            .isEqualTo(403);
        assertThat(edit(session, "{\"memberId\":" + betoMember + ",\"phone\":\"3009998877\"}").getResponse().getStatus())
            .isEqualTo(403);
        assertThat(phoneOf(betoMember)).isEqualTo("3001110002");
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void conVariasAfiliacionesSoloSeVeYSeTocaLaDelEstablecimientoActivo() throws Exception {
        Org uno = orgWithAdmin();
        Org dos = orgWithAdmin();
        Acct ana = user(false);
        long enUno = member(ana, uno.id(), "client", "3001110001", eps(true));
        long enDos = member(ana, dos.id(), "client", "3002220002", eps(true));
        Cookie session = login(ana);
        choose(session, uno.id());
        String viendoUno = body(read(session));
        assertThat(viendoUno).contains("3001110001").doesNotContain("3002220002");
        assertThat(edit(session, "{\"phone\":\"3109990000\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(phoneOf(enUno)).isEqualTo("3109990000");
        assertThat(phoneOf(enDos)).isEqualTo("3002220002");
        choose(session, dos.id());
        assertThat(body(read(session))).contains("3002220002").doesNotContain("3109990000");
    }

    @Test
    void conVariasAfiliacionesSinElegirNoSeExponeNada() throws Exception {
        Org uno = orgWithAdmin();
        Org dos = orgWithAdmin();
        Acct ana = user(false);
        member(ana, uno.id(), "client", "3001110001", eps(true));
        member(ana, dos.id(), "client", "3002220002", eps(true));
        Cookie session = login(ana);
        MvcResult r = read(session);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r)).contains("ESTABLISHMENT_REQUIRED").doesNotContain("3001110001", "3002220002");
    }

    @Test
    void unaAfiliacionInactivaNoDaAccesoANadaDelGimnasio() throws Exception {
        Org org = orgWithAdmin();
        Acct ana = user(false);
        member(ana, org.id(), "client", "3001110001", eps(true));
        Cookie session = login(ana);
        jdbc.update("UPDATE organization_members SET is_active = false WHERE user_id = ?", ana.id());
        assertThat(read(session).getResponse().getStatus()).isEqualTo(403);
        assertThat(edit(session, "{\"phone\":\"3207778899\"}").getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void sinSesionDa401EnLecturaYEscritura() throws Exception {
        assertThat(mvc.perform(get("/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(patch("/me").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"phone\":\"3207778899\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void elMasterSoloVeSuIdentidadYNoEditaNada() throws Exception {
        Acct master = user(true);
        Cookie session = login(master);
        MvcResult r = read(session);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains(master.email()).contains("\"affiliation\":null");
        assertThat(edit(session, "{\"phone\":\"3207778899\"}").getResponse().getStatus()).isEqualTo(403);
    }

    // ---------- Datos de prueba ----------

    private Acct user(boolean platformAdmin) {
        int n = SEQ.incrementAndGet();
        String email = "p" + n + "@nexfit.test";
        String doc = "P" + n;
        long id = jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', ?, ?, 'Pérez', DATE '1990-01-01', ?, ?, ?, true) RETURNING id
            """, Long.class, doc, "Nombre" + n, email, encoder.encode(PASSWORD), platformAdmin);
        return new Acct(id, email, doc);
    }

    /** Gimnasio activo con su Administrador (si no, RN-04 bloquea las rutas operativas). */
    private Org orgWithAdmin() {
        long orgId = jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES ('Gym', 'g@nexfit.test', 'America/Bogota', true) RETURNING id
            """, Long.class);
        Acct admin = user(false);
        member(admin, orgId, "admin", "3000000000", null);
        return new Org(orgId, admin);
    }

    private long eps(boolean active) {
        return jdbc.queryForObject("INSERT INTO health_insurance_providers (name, is_active) VALUES (?, ?) RETURNING id",
            Long.class, "EPS " + SEQ.incrementAndGet(), active);
    }

    /** Afilia al usuario con un rol. El cliente exige EPS y un contacto (RN-25): todo en una sola sentencia. */
    private long member(Acct user, long orgId, String role, String phone, Long epsId) {
        if (role.equals("client")) {
            return jdbc.queryForObject("""
                WITH m AS (INSERT INTO organization_members (organization_id, user_id, phone, eps_id, is_active)
                           VALUES (?, ?, ?, ?, true) RETURNING id),
                     c AS (INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
                           SELECT ?, m.id, 'Luis Pérez', 'Padre', '3000000000', true FROM m),
                     r AS (INSERT INTO member_roles (member_id, role, organization_id)
                           SELECT m.id, 'client', ? FROM m)
                SELECT id FROM m
                """, Long.class, orgId, user.id(), phone, epsId, orgId, orgId);
        }
        long memberId = jdbc.queryForObject("""
            INSERT INTO organization_members (organization_id, user_id, phone, is_active)
            VALUES (?, ?, ?, true) RETURNING id
            """, Long.class, orgId, user.id(), phone);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST(? AS member_role), ?)",
            memberId, role, orgId);
        return memberId;
    }

    private Cookie login(Acct account) throws Exception {
        MvcResult r = mvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(account.email(), PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getCookie("NEXFIT_SESSION");
    }

    private void choose(Cookie session, long orgId) throws Exception {
        assertThat(mvc.perform(post("/auth/establishment").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{\"organizationId\":" + orgId + "}"))
            .andReturn().getResponse().getStatus()).isEqualTo(204);
    }

    private MvcResult read(Cookie session) throws Exception {
        return mvc.perform(get("/me").cookie(session)).andReturn();
    }

    private MvcResult edit(Cookie session, String json) throws Exception {
        return mvc.perform(patch("/me").with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content(json)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private String phoneOf(long memberId) {
        return jdbc.queryForObject("SELECT phone FROM organization_members WHERE id = ?", String.class, memberId);
    }

    private Long epsOf(long memberId) {
        return jdbc.queryForObject("SELECT eps_id FROM organization_members WHERE id = ?", Long.class, memberId);
    }

    private int contactCount(long memberId) {
        return jdbc.queryForObject("SELECT count(*) FROM emergency_contacts WHERE member_id = ?", Integer.class, memberId);
    }

    private int primaryCount(long memberId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM emergency_contacts WHERE member_id = ? AND is_primary", Integer.class, memberId);
    }
}

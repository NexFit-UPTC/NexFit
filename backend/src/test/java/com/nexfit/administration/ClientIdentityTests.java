package com.nexfit.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
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

/** US-51 (NEX-48): corregir la identidad de un cliente antes de que active su cuenta. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ClientIdentityTests {

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

    // ---- utilidades ----------------------------------------------------------------------

    /** Cliente que nunca ha iniciado sesión. */
    private record Target(Account account, long memberId) { }

    private Target newClient(Org org) {
        Account a = fx.user(false, true);
        return new Target(a, fx.member(a, org.id(), "client", true));
    }

    private MvcResult correct(Cookie session, long memberId, String json) throws Exception {
        return mvc.perform(patch("/clients/" + memberId + "/identity").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private Map<String, Object> user(long userId) {
        return jdbc.queryForMap("""
            SELECT CAST(document_type AS text) AS document_type, document_number, first_name, last_name,
                   CAST(birth_date AS text) AS birth_date, email
              FROM users WHERE id = ?""", userId);
    }

    private int audits(long userId) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM audit_events WHERE event_type = 'IDENTITY_CORRECTED' AND entity_id = ?
            """, Integer.class, userId);
    }

    private String change(long userId, String field, String side) {
        return jdbc.queryForObject("""
            SELECT metadata -> 'changes' -> CAST(? AS text) ->> CAST(? AS text) FROM audit_events
             WHERE event_type = 'IDENTITY_CORRECTED' AND entity_id = ?""", String.class, field, side, userId);
    }

    // ---- criterio 1: cuenta sin activar → el cambio se guarda y se audita -----------------------

    @Test
    void elStaffCorrigeLaIdentidadDeUnaCuentaSinActivarYQuedaAuditadaConElValorAnteriorYElNuevo() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        Map<String, Object> before = user(t.account().id());

        MvcResult r = correct(staff, t.memberId(), """
            {"documentType":"ce","documentNumber":"CE-998877","firstName":"Ana María","lastName":"Gómez",
             "birthDate":"1985-04-12","email":"ana.gomez@nexfit.test"}""");

        assertThat(status(r)).as(body(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"memberId\":" + t.memberId(), "\"documentType\":\"CE\"",
            "\"documentNumber\":\"CE-998877\"", "\"firstName\":\"Ana María\"", "\"lastName\":\"Gómez\"",
            "\"birthDate\":\"1985-04-12\"", "\"email\":\"ana.gomez@nexfit.test\"");
        Map<String, Object> after = user(t.account().id());
        assertThat(after).containsEntry("document_type", "CE").containsEntry("document_number", "CE-998877")
            .containsEntry("first_name", "Ana María").containsEntry("last_name", "Gómez")
            .containsEntry("birth_date", "1985-04-12").containsEntry("email", "ana.gomez@nexfit.test");
        assertThat(audits(t.account().id())).isEqualTo(1);
        assertThat(change(t.account().id(), "firstName", "before")).isEqualTo(before.get("first_name"));
        assertThat(change(t.account().id(), "firstName", "after")).isEqualTo("Ana María");
        assertThat(change(t.account().id(), "email", "before")).isEqualTo(before.get("email"));
        assertThat(change(t.account().id(), "email", "after")).isEqualTo("ana.gomez@nexfit.test");
        assertThat(change(t.account().id(), "documentNumber", "before")).isEqualTo(before.get("document_number"));
    }

    @Test
    void corregirSoloUnDatoConservaLosDemasYSoloAuditaEseCampo() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        Map<String, Object> before = user(t.account().id());

        MvcResult r = correct(staff, t.memberId(), "{\"lastName\":\"Rodríguez\"}");

        assertThat(status(r)).isEqualTo(200);
        Map<String, Object> after = user(t.account().id());
        assertThat(after).containsEntry("last_name", "Rodríguez")
            .containsEntry("first_name", before.get("first_name"))
            .containsEntry("email", before.get("email"))
            .containsEntry("document_number", before.get("document_number"));
        assertThat(change(t.account().id(), "lastName", "after")).isEqualTo("Rodríguez");
        assertThat(change(t.account().id(), "firstName", "after")).isNull();
    }

    @Test
    void enviarLosMismosValoresRespondeOkSinCambiosNiAuditoria() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        Map<String, Object> before = user(t.account().id());

        MvcResult r = correct(staff, t.memberId(), "{\"firstName\":\"%s\"}".formatted(before.get("first_name")));

        assertThat(status(r)).isEqualTo(200);
        assertThat(user(t.account().id())).isEqualTo(before);
        assertThat(audits(t.account().id())).isZero();
    }

    @Test
    void despuesDeCorregirElCorreoElTitularEntraConElCorreoNuevo() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);

        assertThat(status(correct(staff, t.memberId(), "{\"email\":\"correcto@nexfit.test\"}"))).isEqualTo(200);

        Cookie session = fx.login(new Account(t.account().id(), "correcto@nexfit.test"));
        assertThat(session).isNotNull();
    }

    // ---- criterio 2: cuenta ya activada o con otras afiliaciones → se rechaza ----------------------

    @Test
    @ReglaDeNegocio("RN-34")
    void siLaCuentaYaInicioSesionSeRechazaYNoCambiaNada() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        fx.login(t.account());
        Map<String, Object> before = user(t.account().id());

        MvcResult r = correct(staff, t.memberId(), "{\"firstName\":\"Otro\",\"email\":\"otro@nexfit.test\"}");

        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("\"code\":\"IDENTITY_LOCKED\"", "solicitud de corrección");
        assertThat(user(t.account().id())).isEqualTo(before);
        assertThat(audits(t.account().id())).isZero();
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void siLaPersonaTieneAfiliacionEnOtroGimnasioSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        fx.member(t.account(), other.id(), "client", true);
        Map<String, Object> before = user(t.account().id());

        MvcResult r = correct(staff, t.memberId(), "{\"firstName\":\"Otro\"}");

        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("\"code\":\"IDENTITY_LOCKED\"");
        assertThat(user(t.account().id())).isEqualTo(before);
        assertThat(audits(t.account().id())).isZero();
    }

    @Test
    void laAfiliacionInactivaEnOtroGimnasioTambienBloquea() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        fx.member(t.account(), other.id(), "client", false);

        assertThat(status(correct(staff, t.memberId(), "{\"firstName\":\"Otro\"}"))).isEqualTo(409);
    }

    // ---- criterio 3: documento o correo de otra persona (RN-26) --------------------------------

    @Test
    void unDocumentoQueYaPerteneceAOtraPersonaSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        Account other = fx.user(false, true);
        String taken = user(other.id()).get("document_number").toString();
        Map<String, Object> before = user(t.account().id());

        MvcResult r = correct(staff, t.memberId(), "{\"documentNumber\":\"%s\",\"firstName\":\"Otro\"}".formatted(taken));

        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("\"code\":\"DOCUMENT_ALREADY_EXISTS\"");
        assertThat(user(t.account().id())).as("no se aplica ni la parte válida").isEqualTo(before);
        assertThat(audits(t.account().id())).isZero();
    }

    @Test
    void unCorreoQueYaPerteneceAOtraPersonaSeRechazaSinImportarMayusculas() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        Account other = fx.user(false, true);
        Map<String, Object> before = user(t.account().id());

        MvcResult r = correct(staff, t.memberId(), "{\"email\":\"%s\"}".formatted(other.email().toUpperCase()));

        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("\"code\":\"EMAIL_ALREADY_EXISTS\"");
        assertThat(user(t.account().id())).isEqualTo(before);
    }

    @Test
    void corregirAlPropioCorreoConOtraCapitalizacionNoCuentaComoConflicto() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);

        MvcResult r = correct(staff, t.memberId(), "{\"email\":\"%s\"}".formatted(t.account().email().toUpperCase()));

        assertThat(status(r)).as(body(r)).isEqualTo(200);
    }

    // ---- criterio 4: menor de 18 años (RN-35) --------------------------------------------------

    @Test
    @ReglaDeNegocio("RN-35")
    void unaFechaDeNacimientoQueDaMenosDeDieciochoAnosSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        Map<String, Object> before = user(t.account().id());
        String almost = LocalDate.now().minusYears(18).plusDays(1).toString();

        MvcResult r = correct(staff, t.memberId(), "{\"birthDate\":\"%s\"}".formatted(almost));

        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r)).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"birthDate\"");
        assertThat(user(t.account().id())).isEqualTo(before);
    }

    @Test
    @ReglaDeNegocio("RN-35")
    void quienCumpleDieciochoHoyPuedeCorregirSuFecha() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        String exactly = LocalDate.now().minusYears(18).toString();

        MvcResult r = correct(staff, t.memberId(), "{\"birthDate\":\"%s\"}".formatted(exactly));

        assertThat(status(r)).as(body(r)).isEqualTo(200);
        assertThat(user(t.account().id())).containsEntry("birth_date", exactly);
    }

    // ---- criterio 5: solo el Staff ----------------------------------------------------------------

    @Test
    void entrenadorClienteYAdministradorRecibenForbiddenYNoCambiaNada() throws Exception {
        Org org = fx.orgWithAdmin();
        Target t = newClient(org);
        Map<String, Object> before = user(t.account().id());
        String json = "{\"firstName\":\"Intruso\"}";

        for (Cookie session : new Cookie[] {fx.login(fx.actor(org.id(), "trainer")),
            fx.login(fx.actor(org.id(), "client")), fx.login(org.admin())}) {
            MvcResult r = correct(session, t.memberId(), json);
            assertThat(status(r)).isEqualTo(403);
            assertThat(body(r)).contains("\"code\":\"ACCESS_DENIED\"");
        }
        assertThat(user(t.account().id())).isEqualTo(before);
    }

    @Test
    void sinSesionResponde401() throws Exception {
        MvcResult r = mvc.perform(patch("/clients/1/identity").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"firstName\":\"X\"}")).andReturn();

        assertThat(status(r)).isEqualTo(401);
    }

    // ---- alcance: solo clientes de este gimnasio ---------------------------------------------------

    @Test
    void unClienteDeOtroGimnasioYUnMiembroQueNoEsClienteResponden404() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target foreign = newClient(other);
        long trainer = fx.member(fx.user(false, true), org.id(), "trainer", true);
        Map<String, Object> before = user(foreign.account().id());

        assertThat(status(correct(staff, foreign.memberId(), "{\"firstName\":\"X\"}"))).isEqualTo(404);
        assertThat(status(correct(staff, trainer, "{\"firstName\":\"X\"}"))).isEqualTo(404);
        assertThat(status(correct(staff, 999_999_999L, "{\"firstName\":\"X\"}"))).isEqualTo(404);
        assertThat(user(foreign.account().id())).isEqualTo(before);
    }

    // ---- validación ---------------------------------------------------------------------------------

    @Test
    void losDatosNoValidosOCamposAjenosSeRechazanConCodigoYCampo() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Target t = newClient(org);
        Map<String, Object> before = user(t.account().id());
        String[][] cases = {
            {"{}", "body"},
            {"{\"phone\":\"3001234567\"}", "phone"},
            {"{\"isActive\":false}", "isActive"},
            {"{\"documentType\":\"XX\"}", "documentType"},
            {"{\"documentNumber\":\"\"}", "documentNumber"},
            {"{\"documentNumber\":\"" + "9".repeat(31) + "\"}", "documentNumber"},
            {"{\"firstName\":\"  \"}", "firstName"},
            {"{\"lastName\":123}", "lastName"},
            {"{\"birthDate\":\"12/04/1985\"}", "birthDate"},
            {"{\"email\":\"sin-arroba\"}", "email"},
        };
        for (String[] c : cases) {
            MvcResult r = correct(staff, t.memberId(), c[0]);
            assertThat(status(r)).as(c[0]).isEqualTo(400);
            assertThat(body(r)).as(c[0]).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"" + c[1] + "\"");
        }
        assertThat(user(t.account().id())).isEqualTo(before);
        assertThat(audits(t.account().id())).isZero();
    }
}

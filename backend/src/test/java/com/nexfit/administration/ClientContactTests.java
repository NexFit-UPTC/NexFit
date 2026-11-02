package com.nexfit.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures;
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

/** US-14 (NEX-42): actualizar datos de contacto, EPS y contactos de emergencia de un cliente. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ClientContactTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private AccessFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new AccessFixtures(jdbc, encoder, mvc);
    }

    private long epsId() {
        return jdbc.queryForObject(
            "INSERT INTO health_insurance_providers (name, is_active) VALUES ('EPS Test', true) RETURNING id",
            Long.class);
    }

    private long memberOf(long orgId, long userId) {
        return jdbc.queryForObject(
            "SELECT id FROM organization_members WHERE organization_id = ? AND user_id = ?",
            Long.class, orgId, userId);
    }

    private Cookie staffCookie(AccessFixtures.Org org) throws Exception {
        return fixtures.login(fixtures.actor(org.id(), "staff"));
    }

    private Cookie clientCookie(AccessFixtures.Account client) throws Exception {
        return fixtures.login(client);
    }

    private String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MvcResult getContact(Cookie actor, long memberId) throws Exception {
        return mvc.perform(get("/clients/" + memberId + "/contact").cookie(actor)).andReturn();
    }

    private MvcResult patchContact(Cookie actor, long memberId, String json) throws Exception {
        return mvc.perform(patch("/clients/" + memberId + "/contact").with(csrf()).cookie(actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private MvcResult patchMe(Cookie actor, String json) throws Exception {
        return mvc.perform(patch("/me").with(csrf()).cookie(actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private int auditTypeCount(long orgId, String type) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE organization_id = ? AND event_type = ?",
            Integer.class, orgId, type);
    }

    private String phoneOf(long memberId) {
        return jdbc.queryForObject("SELECT phone FROM organization_members WHERE id = ?", String.class, memberId);
    }

    private Long epsOf(long memberId) {
        return jdbc.queryForObject("SELECT eps_id FROM organization_members WHERE id = ?", Long.class, memberId);
    }

    private Boolean consentOf(long memberId) {
        return jdbc.queryForObject("SELECT health_consent_at IS NOT NULL FROM organization_members WHERE id = ?",
            Boolean.class, memberId);
    }

    private int contactCount(long memberId) {
        return jdbc.queryForObject("SELECT count(*) FROM emergency_contacts WHERE member_id = ?",
            Integer.class, memberId);
    }

    @Test
    void staffConsultaContactoYAudita() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        MvcResult r = getContact(staff, memberId);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"memberId\":" + memberId).contains("emergencyContacts");
        assertThat(body(r)).doesNotContain(client.email());
        assertThat(auditTypeCount(org.id(), "HEALTH_CONTACT_DATA_ACCESSED")).isEqualTo(1);
    }

    @Test
    void adminConsultaContactoYAudita() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie admin = fixtures.login(org.admin());
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        assertThat(getContact(admin, memberId).getResponse().getStatus()).isEqualTo(200);
        assertThat(auditTypeCount(org.id(), "HEALTH_CONTACT_DATA_ACCESSED")).isEqualTo(1);
    }

    @Test
    void clienteConsultaSuPropioContacto() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        assertThat(getContact(clientCookie(client), memberId).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void staffActualizaTelefonoYEpsConConsentimiento() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());
        long newEps = epsId();
        assertThat(consentOf(memberId)).isFalse();

        MvcResult r = patchContact(staff, memberId,
            "{\"phone\":\"3009998888\",\"epsId\":" + newEps + ",\"healthConsent\":true}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(phoneOf(memberId)).isEqualTo("3009998888");
        assertThat(epsOf(memberId)).isEqualTo(newEps);
        assertThat(consentOf(memberId)).isTrue();
    }

    @Test
    void staffActualizaContactosSoloEnSuGimnasio() throws Exception {
        AccessFixtures.Org orgA = fixtures.orgWithAdmin();
        Cookie staffA = staffCookie(orgA);
        AccessFixtures.Account clientA = fixtures.actor(orgA.id(), "client");
        long memberA = memberOf(orgA.id(), clientA.id());
        AccessFixtures.Org orgB = fixtures.orgWithAdmin();
        AccessFixtures.Account clientB = fixtures.actor(orgB.id(), "client");
        long memberB = memberOf(orgB.id(), clientB.id());

        MvcResult r = patchContact(staffA, memberA,
            "{\"emergencyContacts\":[{\"fullName\":\"Nuevo Contacto\",\"relationship\":\"Hermano\",\"phone\":\"3001112222\",\"primary\":true}]}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(contactCount(memberA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT full_name FROM emergency_contacts WHERE member_id = ?",
            String.class, memberA)).isEqualTo("Nuevo Contacto");

        assertThat(patchContact(staffA, memberB, "{\"phone\":\"3001112222\"}")
            .getResponse().getStatus()).isEqualTo(404);
        assertThat(contactCount(memberB)).isEqualTo(1);
    }

    @Test
    void clienteActualizaSuPropioTelefonoYContactos() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        MvcResult r = patchContact(clientCookie(client), memberId,
            "{\"phone\":\"3005554444\",\"emergencyContacts\":[{\"fullName\":\"Contacto Propio\",\"relationship\":\"Madre\",\"phone\":\"3005554444\",\"primary\":true}]}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(phoneOf(memberId)).isEqualTo("3005554444");
    }

    @Test
    void clienteNoPuedeActualizarOtroCliente() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        AccessFixtures.Account clientA = fixtures.actor(org.id(), "client");
        AccessFixtures.Account clientB = fixtures.actor(org.id(), "client");
        long memberB = memberOf(org.id(), clientB.id());

        assertThat(patchContact(clientCookie(clientA), memberB, "{\"phone\":\"3001112222\"}")
            .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void entrenadorNoPuedeModificar() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie trainer = fixtures.login(fixtures.actor(org.id(), "trainer"));
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        assertThat(patchContact(trainer, memberId, "{\"phone\":\"3001112222\"}")
            .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void adminNoPuedeModificar() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie admin = fixtures.login(org.admin());
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        assertThat(patchContact(admin, memberId, "{\"phone\":\"3001112222\"}")
            .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @ReglaDeNegocio("RN-34")
    void datosIdentidadDan400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        MvcResult r = patchContact(staff, memberId, "{\"firstName\":\"Otro\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("firstName");
    }

    @Test
    void sinUltimoContactoDa400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        MvcResult r = patchContact(staff, memberId, "{\"emergencyContacts\":[]}");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("emergencyContacts");
        assertThat(contactCount(memberId)).isEqualTo(1);
    }

    @Test
    void dosPrincipalesDan400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        MvcResult r = patchContact(staff, memberId,
            "{\"emergencyContacts\":[{\"fullName\":\"Uno\",\"relationship\":\"Padre\",\"phone\":\"3001112222\",\"primary\":true},"
            + "{\"fullName\":\"Dos\",\"relationship\":\"Madre\",\"phone\":\"3003334444\",\"primary\":true}]}");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(contactCount(memberId)).isEqualTo(1);
    }

    @Test
    void cambioEpsSinConsentimientoDa400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());
        Long oldEps = epsOf(memberId);
        long newEps = epsId();

        MvcResult r = patchContact(staff, memberId, "{\"epsId\":" + newEps + "}");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("healthConsent");
        assertThat(epsOf(memberId)).isEqualTo(oldEps);
    }

    @Test
    void epsVaciaDa400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        MvcResult r = patchContact(staff, memberId, "{\"epsId\":null}");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("epsId");
    }

    @Test
    void epsInexistenteDa404() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        Cookie staff = staffCookie(org);
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());

        MvcResult r = patchContact(staff, memberId, "{\"epsId\":999999999,\"healthConsent\":true}");
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void meCambioEpsSinConsentimientoDa400() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());
        Long oldEps = epsOf(memberId);
        long newEps = epsId();

        MvcResult r = patchMe(clientCookie(client), "{\"epsId\":" + newEps + "}");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(epsOf(memberId)).isEqualTo(oldEps);
    }

    @Test
    void meCambioEpsConConsentimientoActualizaConsentimiento() throws Exception {
        AccessFixtures.Org org = fixtures.orgWithAdmin();
        AccessFixtures.Account client = fixtures.actor(org.id(), "client");
        long memberId = memberOf(org.id(), client.id());
        long newEps = epsId();
        assertThat(consentOf(memberId)).isFalse();

        MvcResult r = patchMe(clientCookie(client),
            "{\"epsId\":" + newEps + ",\"healthConsent\":true}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(epsOf(memberId)).isEqualTo(newEps);
        assertThat(consentOf(memberId)).isTrue();
    }
}

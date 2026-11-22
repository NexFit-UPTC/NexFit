package com.nexfit.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** US-15 (NEX-43): buscar clientes y consultar su estado. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ClientQueryTests {

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

    private Cookie staff(Org org) throws Exception {
        return fx.login(fx.actor(org.id(), "staff"));
    }

    /** Persona nueva con nombre dado, afiliada como cliente al gimnasio; devuelve el id de la afiliación. */
    private long client(Org org, String first, String last) {
        return client(person(first, last), org);
    }

    private Account person(String first, String last) {
        Account account = fx.user(false, true);
        jdbc.update("UPDATE users SET first_name = ?, last_name = ? WHERE id = ?", first, last, account.id());
        return account;
    }

    private long client(Account account, Org org) {
        return fx.member(account, org.id(), "client", true);
    }

    /** Membresía del cliente; las fechas son días desde hoy (la base y el gimnasio pueden diferir en un día). */
    private void membership(Org org, long memberId, String status, int startDays, int endDays) {
        long plan = jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, 'Plan ' || gen_random_uuid(), 50000, 30, true) RETURNING id
            """, Long.class, org.id());
        if (status.equals("active")) {
            jdbc.update("""
                INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id,
                                         start_date, end_date, status)
                VALUES (?, ?, ?, ?, CURRENT_DATE + CAST(? AS int), CURRENT_DATE + CAST(? AS int),
                        CAST(? AS membership_status))
                """, org.id(), memberId, plan, org.adminMemberId(), startDays, endDays, status);
        } else {
            // Suspendida o cancelada: la tabla exige motivo, fecha y quién cambió el estado.
            jdbc.update("""
                INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id,
                                         start_date, end_date, status, status_reason, status_changed_at,
                                         status_changed_by_member_id)
                VALUES (?, ?, ?, ?, CURRENT_DATE + CAST(? AS int), CURRENT_DATE + CAST(? AS int),
                        CAST(? AS membership_status), 'Prueba', now(), ?)
                """, org.id(), memberId, plan, org.adminMemberId(), startDays, endDays, status,
                org.adminMemberId());
        }
    }

    private MvcResult list(Cookie session, String query) throws Exception {
        return mvc.perform(get("/clients" + query).cookie(session)).andReturn();
    }

    private MvcResult detail(Cookie session, long memberId) throws Exception {
        return mvc.perform(get("/clients/" + memberId).cookie(session)).andReturn();
    }

    /** Búsqueda por {@code q}; va como parámetro para que MockMvc la codifique una sola vez. */
    private MvcResult search(Cookie session, String text) throws Exception {
        return mvc.perform(get("/clients").param("q", text).cookie(session)).andReturn();
    }

    private static String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private int audits(long orgId) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM audit_events WHERE organization_id = ? AND event_type = 'HEALTH_CONTACT_DATA_ACCESSED'
            """, Integer.class, orgId);
    }

    // ---- criterio 1: el Staff busca por nombre o documento -------------------------------------

    // Ve solo clientes de su gimnasio, con el estado de su afiliación y de su membresía efectiva.
    @Test
    void elStaffBuscaPorNombreYVeSoloClientesDeSuGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie staff = staff(org);
        long laura = client(org, "Laura", "Gómez");
        client(org, "Pedro", "Ramírez");
        client(other, "Laura", "Gómez");
        membership(org, laura, "active", -10, 20);

        MvcResult r = search(staff, "laura gómez");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"totalElements\":1", "\"memberId\":" + laura, "\"firstName\":\"Laura\"",
            "\"active\":true", "\"membershipStatus\":\"active\"");
        assertThat(body(r)).doesNotContain("Pedro");
    }

    @Test
    void elStaffBuscaPorDocumento() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);
        Account target = person("Camila", "Rojas");
        long member = client(target, org);
        client(org, "Otra", "Persona");
        String document = jdbc.queryForObject("SELECT document_number FROM users WHERE id = ?", String.class,
            target.id());

        MvcResult r = search(staff, document);

        assertThat(body(r)).contains("\"totalElements\":1", "\"memberId\":" + member,
            "\"documentNumber\":\"" + document + "\"");
    }

    @Test
    void laBusquedaNoTrataComoPatronLosComodines() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);
        client(org, "Laura", "Gómez");

        assertThat(body(search(staff, "%"))).contains("\"totalElements\":0");
        assertThat(body(search(staff, "_"))).contains("\"totalElements\":0");
        assertThat(body(search(staff, "L%a"))).contains("\"totalElements\":0");
    }

    @Test
    void elListadoSoloIncluyeClientesNoPersonalDelGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);
        long member = client(org, "Laura", "Gómez");

        // El administrador y el staff del gimnasio no son clientes: no salen en el listado.
        MvcResult r = list(staff, "");

        assertThat(body(r)).contains("\"totalElements\":1", "\"memberId\":" + member);
    }

    // ---- estado de la membresía efectiva ---------------------------------------------------------

    @Test
    void elListadoMuestraLaMembresiaEfectivaDeCadaCliente() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);
        long active = client(org, "Ana", "Activa");
        long expired = client(org, "Ana", "Vencida");
        long scheduled = client(org, "Ana", "Programada");
        long suspended = client(org, "Ana", "Suspendida");
        long cancelled = client(org, "Ana", "Cancelada");
        long none = client(org, "Ana", "Sinmembresia");
        membership(org, active, "active", -10, 20);
        membership(org, expired, "active", -90, -30);
        membership(org, scheduled, "active", 10, 40);
        membership(org, suspended, "suspended", -10, 20);
        membership(org, cancelled, "cancelled", -10, 20);

        String json = body(list(staff, "?size=50"));

        assertThat(status(list(staff, ""))).isEqualTo(200);
        assertThat(entryOf(json, active)).contains("\"membershipStatus\":\"active\"");
        assertThat(entryOf(json, expired)).contains("\"membershipStatus\":\"expired\"");
        assertThat(entryOf(json, scheduled)).contains("\"membershipStatus\":\"scheduled\"");
        assertThat(entryOf(json, suspended)).contains("\"membershipStatus\":\"suspended\"");
        assertThat(entryOf(json, cancelled)).contains("\"membershipStatus\":\"cancelled\"");
        assertThat(entryOf(json, none)).contains("\"membershipStatus\":null", "\"membershipEndDate\":null");
    }

    @Test
    void conVariasMembresiasSeMuestraLaVigenteAunqueHayaUnaVencidaMasReciente() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);
        long member = client(org, "Ana", "Renovada");
        membership(org, member, "active", -90, -30);
        membership(org, member, "active", -10, 20);

        assertThat(entryOf(body(list(staff, "")), member)).contains("\"membershipStatus\":\"active\"");
    }

    /** Fragmento JSON de la fila de ese cliente dentro del listado. */
    private static String entryOf(String json, long memberId) {
        int start = json.indexOf("{\"memberId\":" + memberId + ",");
        assertThat(start).as("el cliente %d está en el listado", memberId).isNotNegative();
        return json.substring(start, json.indexOf('}', start) + 1);
    }

    @Test
    void elFiltroDeEstadoSeparaLasAfiliacionesActivasDeLasInactivas() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);
        long on = client(org, "Ana", "Activa");
        long off = client(org, "Ana", "Inactiva");
        jdbc.update("UPDATE organization_members SET is_active = false WHERE id = ?", off);

        assertThat(body(list(staff, ""))).contains("\"totalElements\":2");
        assertThat(body(list(staff, "?active=true"))).contains("\"totalElements\":1", "\"memberId\":" + on);
        MvcResult inactive = list(staff, "?active=false");
        assertThat(body(inactive)).contains("\"totalElements\":1", "\"memberId\":" + off, "\"active\":false");
    }

    // ---- criterio 2: el Administrador ve el listado paginado de su gimnasio -------------------

    @Test
    void elAdministradorVeElListadoPaginadoDeSuGimnasioYNoDeOtros() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        client(org, "Ana", "Alvarez");
        client(org, "Beatriz", "Benítez");
        client(org, "Carla", "Castro");
        client(other, "Zoraida", "Secreta");

        MvcResult first = list(admin, "?size=2&page=0");
        MvcResult second = list(admin, "?size=2&page=1");

        assertThat(status(first)).isEqualTo(200);
        assertThat(body(first)).contains("\"totalElements\":3", "\"totalPages\":2", "\"size\":2",
            "\"lastName\":\"Alvarez\"", "\"lastName\":\"Benítez\"");
        assertThat(body(second)).contains("\"page\":1", "\"lastName\":\"Castro\"");
        assertThat(body(first) + body(second)).doesNotContain("Zoraida", "Secreta");
    }

    @Test
    void elTamanoDePaginaTieneTope() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        client(org, "Ana", "Alvarez");

        assertThat(body(list(admin, "?size=1000"))).contains("\"size\":50");
        assertThat(body(list(admin, "?size=0"))).contains("\"size\":20");
        assertThat(body(list(admin, "?page=-3"))).contains("\"page\":0");
    }

    // ---- criterio 3: Cliente y Entrenador reciben 403 --------------------------------------------

    @Test
    void unClienteOUnEntrenadorNoPuedenUsarLaBusquedaGeneral() throws Exception {
        Org org = fx.orgWithAdmin();
        long member = client(org, "Laura", "Gómez");

        for (String role : new String[] {"client", "trainer"}) {
            Cookie session = fx.login(fx.actor(org.id(), role));
            MvcResult r = list(session, "");
            assertThat(status(r)).as(role).isEqualTo(403);
            assertThat(body(r)).as(role).contains("ACCESS_DENIED").doesNotContain("Laura");
            assertThat(status(detail(session, member))).as(role + " detalle").isEqualTo(403);
        }
        assertThat(audits(org.id())).isZero();
    }

    @Test
    void sinSesionSeRespondeUnauthorized() throws Exception {
        assertThat(status(mvc.perform(get("/clients")).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(get("/clients/1")).andReturn())).isEqualTo(401);
    }

    @Test
    void laBusquedaDemasiadoLargaSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);

        MvcResult r = search(staff, "a".repeat(101));

        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r)).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"q\"");
    }

    // ---- criterio 4: un cliente afiliado a dos gimnasios -------------------------------------------

    // El Staff de A no ve membresías ni datos de contacto de B.
    @Test
    void elStaffNoVeMembresiasNiContactosDelOtroGimnasio() throws Exception {
        Org a = fx.orgWithAdmin();
        Org b = fx.orgWithAdmin();
        Cookie staffA = staff(a);
        Account person = person("Laura", "Gómez");
        long memberA = client(person, a);
        long memberB = client(person, b);
        membership(b, memberB, "active", -10, 20);
        jdbc.update("UPDATE organization_members SET phone = '3009999999' WHERE id = ?", memberB);
        jdbc.update("UPDATE emergency_contacts SET full_name = 'Contacto Secreto B', phone = '3008888888'"
            + " WHERE member_id = ?", memberB);
        jdbc.update("UPDATE organization_members SET phone = '3001111111' WHERE id = ?", memberA);

        MvcResult listA = list(staffA, "");
        MvcResult detailA = detail(staffA, memberA);

        assertThat(body(listA)).contains("\"memberId\":" + memberA, "\"membershipStatus\":null");
        assertThat(status(detailA)).isEqualTo(200);
        assertThat(body(detailA)).contains("\"membership\":null", "\"phone\":\"3001111111\"")
            .doesNotContain("Contacto Secreto B", "3008888888", "3009999999");
        // La afiliación de B no se puede abrir desde A: igual que un id inexistente.
        assertThat(status(detail(staffA, memberB))).isEqualTo(404);
    }

    // ---- criterio 5: el detalle se audita; el listado no ---------------------------------------------

    @Test
    void elDetalleMuestraEpsYContactosYQuedaAuditado() throws Exception {
        Org org = fx.orgWithAdmin();
        Account staffAccount = fx.actor(org.id(), "staff");
        Cookie staff = fx.login(staffAccount);
        Account person = person("Laura", "Gómez");
        long member = client(person, org);
        membership(org, member, "active", -10, 20);
        int before = audits(org.id());

        MvcResult r = detail(staff, member);

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"memberId\":" + member, "\"firstName\":\"Laura\"", "\"epsName\":\"EPS\"",
            "\"emergencyContacts\":[{", "\"fullName\":\"Luis Pérez\"", "\"membership\":{\"status\":\"active\"");
        assertThat(audits(org.id())).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_events WHERE organization_id = ? AND event_type = 'HEALTH_CONTACT_DATA_ACCESSED'
               AND actor_user_id = ? AND entity_id = ?
            """, Integer.class, org.id(), staffAccount.id(), member)).isEqualTo(1);
    }

    @Test
    void elAdministradorTambienPuedeVerElDetalleYQuedaAuditado() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long member = client(org, "Laura", "Gómez");

        assertThat(status(detail(admin, member))).isEqualTo(200);
        assertThat(audits(org.id())).isEqualTo(1);
    }

    @Test
    void elListadoNoMuestraEpsNiContactosYNoAudita() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);
        long member = client(org, "Laura", "Gómez");
        jdbc.update("UPDATE organization_members SET phone = '3001234567' WHERE id = ?", member);

        MvcResult r = list(staff, "");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("Laura").doesNotContain("eps", "Eps", "emergency", "3001234567",
            "Luis Pérez", "phone", "email");
        assertThat(audits(org.id())).isZero();
    }

    @Test
    void elDetalleDeUnClienteInexistenteOQueNoEsClienteDa404SinAuditar() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);

        assertThat(status(detail(staff, 987_654_321L))).isEqualTo(404);
        // El administrador del gimnasio es un afiliado, pero no un cliente.
        assertThat(status(detail(staff, org.adminMemberId()))).isEqualTo(404);
        assertThat(audits(org.id())).isZero();
    }

    @Test
    void elDetalleDeUnClienteInactivoSeConsulta() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = staff(org);
        long member = client(org, "Laura", "Gómez");
        jdbc.update("UPDATE organization_members SET is_active = false WHERE id = ?", member);

        MvcResult r = detail(staff, member);

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"active\":false");
    }
}

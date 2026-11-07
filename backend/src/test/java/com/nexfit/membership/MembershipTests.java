package com.nexfit.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

/** NEX-49 a NEX-57, NEX-66 y NEX-67 (US-20 a US-25): contratar, consultar, renovar, suspender y cancelar. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class MembershipTests {

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

    private record Client(Account account, long memberId) { }

    private Client client(long orgId) {
        Account a = fx.user(false, true);
        return new Client(a, fx.member(a, orgId, "client", true));
    }

    private LocalDate today(long orgId) {
        return jdbc.queryForObject("SELECT fn_org_local_date(?, now())", LocalDate.class, orgId);
    }

    private static final java.util.concurrent.atomic.AtomicInteger PLAN_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    private long plan(long orgId, int days, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, ?, 50000, ?, ?) RETURNING id
            """, Long.class, orgId, "Plan " + PLAN_SEQ.incrementAndGet(), days, active);
    }

    /** Inserta una membresía directamente (para armar estados que la API no permite crear). */
    private long insert(Org org, long memberId, long planId, LocalDate start, LocalDate end, String status) {
        return jdbc.queryForObject("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status, status_reason, status_changed_at, status_changed_by_member_id)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS membership_status),
                    CASE WHEN ? <> 'active' THEN 'Preparada' END,
                    CASE WHEN ? <> 'active' THEN now() END,
                    CASE WHEN ? <> 'active' THEN CAST(? AS bigint) END) RETURNING id
            """, Long.class, org.id(), memberId, planId, org.adminMemberId(), start, end, status, status, status,
            status, org.adminMemberId());
    }

    private MvcResult postJ(Cookie session, String uri, String json) throws Exception {
        return mvc.perform(post(uri).with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult patchJ(Cookie session, String uri, String json) throws Exception {
        return mvc.perform(patch(uri).with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult getJ(Cookie session, String uri) throws Exception {
        return mvc.perform(get(uri).cookie(session)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static long membershipIdOf(String json) {
        return Long.parseLong(json.replaceAll("(?s).*?\"membership\":\\{\"id\":(\\d+).*", "$1"));
    }

    private int membershipCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM memberships WHERE organization_id = ?", Integer.class, orgId);
    }

    private int paymentCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE organization_id = ?", Integer.class, orgId);
    }

    private static String contract(long memberId, long planId, String extra) {
        return "{\"memberId\":%d,\"planId\":%d%s}".formatted(memberId, planId, extra);
    }

    // ---- US-20 · contratar ---------------------------------------------------------------

    @Test
    void elStaffContrataConPagoYTodoQuedaEnUnaOperacion() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Client c = client(org.id());
        long plan = plan(org.id(), 30, true);

        MvcResult r = postJ(staff, "/memberships",
            contract(c.memberId(), plan, ",\"payment\":{\"method\":\"cash\",\"reference\":\"R-1\"}"));

        assertThat(status(r)).isEqualTo(201);
        long id = membershipIdOf(body(r));
        var row = jdbc.queryForMap("SELECT start_date, end_date, status FROM memberships WHERE id = ?", id);
        LocalDate today = today(org.id());
        assertThat(row.get("start_date")).hasToString(today.toString());
        assertThat(row.get("end_date")).hasToString(today.plusDays(29).toString());
        assertThat(row.get("status")).hasToString("active");
        var pay = jdbc.queryForMap("SELECT membership_id, amount, status FROM payments WHERE member_id = ?",
            c.memberId());
        assertThat(((Number) pay.get("membership_id")).longValue()).isEqualTo(id);
        assertThat(pay.get("status")).hasToString("completed");
        assertThat(((java.math.BigDecimal) pay.get("amount")).intValue()).isEqualTo(50000);
        assertThat(body(r)).contains("\"effectiveStatus\":\"current\"");
    }

    @Test
    void sePuedeContratarSinPagoYConInicioFuturo() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Client c = client(org.id());
        long plan = plan(org.id(), 10, true);
        LocalDate start = today(org.id()).plusDays(5);

        MvcResult r = postJ(staff, "/memberships", contract(c.memberId(), plan, ",\"startDate\":\"" + start + "\""));

        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"endDate\":\"" + start.plusDays(9) + "\"")
            .contains("\"effectiveStatus\":\"scheduled\"").contains("\"payment\":null");
        assertThat(paymentCount(org.id())).isZero();
    }

    @Test
    void contratacionInvalidaSeRechazaSinCrearNada() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Client c = client(org.id());
        long plan = plan(org.id(), 30, true);
        long inactive = plan(org.id(), 30, false);
        long foreignPlan = plan(other.id(), 30, true);
        long staffMember = fx.member(fx.user(false, true), org.id(), "staff", true);
        LocalDate yesterday = today(org.id()).minusDays(1);

        assertThat(status(postJ(staff, "/memberships",
            contract(c.memberId(), plan, ",\"startDate\":\"" + yesterday + "\"")))).isEqualTo(400);
        assertThat(status(postJ(staff, "/memberships", contract(c.memberId(), inactive, "")))).isEqualTo(400);
        assertThat(status(postJ(staff, "/memberships", contract(c.memberId(), foreignPlan, "")))).isEqualTo(400);
        assertThat(status(postJ(staff, "/memberships", contract(staffMember, plan, "")))).isEqualTo(400);
        assertThat(status(postJ(staff, "/memberships", contract(c.memberId(), plan,
            ",\"payment\":{\"method\":\"bitcoin\"}")))).isEqualTo(400);
        assertThat(status(postJ(staff, "/memberships", "{\"planId\":" + plan + "}"))).isEqualTo(400);

        assertThat(membershipCount(org.id())).isZero();
        assertThat(paymentCount(org.id())).isZero();
    }

    @Test
    void noSePermiteSolaparOtraMembresiaNoCancelada() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Client c = client(org.id());
        long plan = plan(org.id(), 30, true);

        assertThat(status(postJ(staff, "/memberships", contract(c.memberId(), plan, "")))).isEqualTo(201);
        MvcResult second = postJ(staff, "/memberships", contract(c.memberId(), plan, ""));

        assertThat(status(second)).isEqualTo(409);
        assertThat(body(second)).contains("MEMBERSHIP_OVERLAP");
        assertThat(membershipCount(org.id())).isEqualTo(1);
    }

    @Test
    void soloElStaffContrata() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = client(org.id());
        long plan = plan(org.id(), 30, true);
        String json = contract(c.memberId(), plan, "");

        assertThat(status(postJ(fx.login(org.admin()), "/memberships", json))).isEqualTo(403);
        assertThat(status(postJ(fx.login(c.account()), "/memberships", json))).isEqualTo(403);
        assertThat(membershipCount(org.id())).isZero();
    }

    @Test
    void laMembresiaNoApareceEnElOtroGimnasioDelCliente() throws Exception {
        Org a = fx.orgWithAdmin();
        Org b = fx.orgWithAdmin();
        Account person = fx.user(false, true);
        long inA = fx.member(person, a.id(), "client", true);
        fx.member(person, b.id(), "client", true);
        Cookie staffA = fx.login(fx.actor(a.id(), "staff"));
        assertThat(status(postJ(staffA, "/memberships", contract(inA, plan(a.id(), 30, true), "")))).isEqualTo(201);

        // El cliente entra al gimnasio B: no ve nada de A.
        Cookie session = fx.login(person);
        mvc.perform(post("/auth/establishment").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{\"organizationId\":" + b.id() + "}")).andReturn();
        MvcResult mine = getJ(session, "/memberships");

        assertThat(status(mine)).isEqualTo(200);
        assertThat(body(mine)).isEqualTo("[]");
    }

    // ---- US-21 / US-22 · consultar y listar ----------------------------------------------

    @Test
    void elEstadoEfectivoSeCalculaConLaFechaLocalDelGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        long plan = plan(org.id(), 30, true);
        LocalDate today = today(org.id());
        long expired = insert(org, client(org.id()).memberId(), plan, today.minusDays(40), today.minusDays(1), "active");
        long endsToday = insert(org, client(org.id()).memberId(), plan, today.minusDays(29), today, "active");
        long future = insert(org, client(org.id()).memberId(), plan, today.plusDays(2), today.plusDays(31), "active");
        long suspended = insert(org, client(org.id()).memberId(), plan, today.minusDays(3), today.plusDays(10),
            "suspended");

        assertThat(body(getJ(staff, "/memberships/" + expired))).contains("\"effectiveStatus\":\"expired\"");
        assertThat(body(getJ(staff, "/memberships/" + endsToday))).contains("\"effectiveStatus\":\"current\"");
        assertThat(body(getJ(staff, "/memberships/" + future))).contains("\"effectiveStatus\":\"scheduled\"");
        assertThat(body(getJ(staff, "/memberships/" + suspended))).contains("\"effectiveStatus\":\"suspended\"");
        assertThat(body(getJ(staff, "/memberships?status=current"))).contains("\"id\":" + endsToday)
            .doesNotContain("\"id\":" + expired, "\"id\":" + suspended, "\"id\":" + future);
        assertThat(body(getJ(staff, "/memberships?status=expired"))).contains("\"id\":" + expired)
            .doesNotContain("\"id\":" + endsToday);
        assertThat(status(getJ(staff, "/memberships?status=otro"))).isEqualTo(400);
    }

    @Test
    void porVencerIncluyeDeHoyAHoyMasNYSoloLasVigentes() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        long plan = plan(org.id(), 30, true);
        LocalDate today = today(org.id());
        long hoy = insert(org, client(org.id()).memberId(), plan, today.minusDays(29), today, "active");
        long limite = insert(org, client(org.id()).memberId(), plan, today.minusDays(23), today.plusDays(7), "active");
        long lejos = insert(org, client(org.id()).memberId(), plan, today.minusDays(21), today.plusDays(9), "active");
        long vencida = insert(org, client(org.id()).memberId(), plan, today.minusDays(40), today.minusDays(1), "active");
        long suspendida = insert(org, client(org.id()).memberId(), plan, today.minusDays(20), today.plusDays(3),
            "suspended");
        long cancelada = insert(org, client(org.id()).memberId(), plan, today.minusDays(20), today.plusDays(3),
            "cancelled");

        String list = body(getJ(staff, "/memberships?expiringInDays=7"));

        assertThat(list).contains("\"id\":" + hoy, "\"id\":" + limite)
            .doesNotContain("\"id\":" + lejos, "\"id\":" + vencida, "\"id\":" + suspendida, "\"id\":" + cancelada);
        assertThat(body(getJ(staff, "/memberships?expiringInDays=9"))).contains("\"id\":" + lejos);
        assertThat(status(getJ(staff, "/memberships?expiringInDays=-1"))).isEqualTo(400);
    }

    @Test
    void elClienteSoloVeSusMembresiasYElPersonalVeElHistorialDelGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        long plan = plan(org.id(), 30, true);
        LocalDate today = today(org.id());
        Client mine = client(org.id());
        Client theirs = client(org.id());
        long own = insert(org, mine.memberId(), plan, today, today.plusDays(29), "active");
        long foreign = insert(org, theirs.memberId(), plan, today, today.plusDays(29), "active");
        Client outsider = client(other.id());
        long outsiderPlan = plan(other.id(), 30, true);
        long hidden = insert(other, outsider.memberId(), outsiderPlan, today, today.plusDays(29), "active");

        Cookie client = fx.login(mine.account());
        assertThat(body(getJ(client, "/memberships"))).contains("\"id\":" + own).doesNotContain("\"id\":" + foreign);
        assertThat(body(getJ(client, "/memberships?memberId=" + theirs.memberId())))
            .contains("\"id\":" + own).doesNotContain("\"id\":" + foreign);
        assertThat(status(getJ(client, "/memberships/" + foreign))).isEqualTo(404);
        assertThat(status(getJ(client, "/memberships/" + hidden))).isEqualTo(404);

        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        assertThat(body(getJ(staff, "/memberships?memberId=" + theirs.memberId()))).contains("\"id\":" + foreign)
            .doesNotContain("\"id\":" + own);
        assertThat(status(getJ(staff, "/memberships/" + hidden))).isEqualTo(404);
    }

    // ---- US-23 · renovar -----------------------------------------------------------------

    @Test
    void renovarUnaVigenteCreaOtraDesdeElDiaSiguienteAlVencimientoConPago() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Client c = client(org.id());
        long plan = plan(org.id(), 30, true);
        long other = plan(org.id(), 7, true);
        LocalDate today = today(org.id());
        long current = insert(org, c.memberId(), plan, today.minusDays(20), today.plusDays(9), "active");

        MvcResult r = postJ(staff, "/memberships/" + current + "/renew",
            "{\"planId\":" + other + ",\"payment\":{\"method\":\"transfer\",\"status\":\"pending\"}}");

        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"startDate\":\"" + today.plusDays(10) + "\"")
            .contains("\"endDate\":\"" + today.plusDays(16) + "\"").contains("\"status\":\"pending\"");
        assertThat(membershipCount(org.id())).isEqualTo(2);
        assertThat(paymentCount(org.id())).isEqualTo(1);
    }

    @Test
    void unaMembresiaVencidaNoSeRenuevaYSeSolapaSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        long plan = plan(org.id(), 30, true);
        long inactive = plan(org.id(), 30, false);
        LocalDate today = today(org.id());
        Client expiredClient = client(org.id());
        long expired = insert(org, expiredClient.memberId(), plan, today.minusDays(40), today.minusDays(1), "active");
        Client chained = client(org.id());
        long current = insert(org, chained.memberId(), plan, today.minusDays(5), today.plusDays(5), "active");
        insert(org, chained.memberId(), plan, today.plusDays(6), today.plusDays(20), "active");

        MvcResult notRenewable = postJ(staff, "/memberships/" + expired + "/renew", "{}");
        assertThat(status(notRenewable)).isEqualTo(409);
        assertThat(body(notRenewable)).contains("MEMBERSHIP_NOT_RENEWABLE");

        MvcResult overlap = postJ(staff, "/memberships/" + current + "/renew", "{}");
        assertThat(status(overlap)).isEqualTo(409);
        assertThat(body(overlap)).contains("MEMBERSHIP_OVERLAP");

        assertThat(status(postJ(staff, "/memberships/" + current + "/renew", "{\"planId\":" + inactive + "}")))
            .isEqualTo(400);
        assertThat(status(postJ(staff, "/memberships/999999999/renew", "{}"))).isEqualTo(404);
    }

    // ---- US-24 / US-25 · suspender, reactivar y cancelar ---------------------------------

    private long futureBooking(Org org, long memberId) {
        long branch = fx.branch(org.id());
        long classId = jdbc.queryForObject("""
            INSERT INTO classes (organization_id, branch_id, name, starts_at, duration_minutes, capacity, status)
            VALUES (?, ?, 'Spinning', now() + interval '3 days', 60, 10, 'scheduled') RETURNING id
            """, Long.class, org.id(), branch);
        return jdbc.queryForObject("""
            INSERT INTO class_bookings (organization_id, class_id, member_id, status)
            VALUES (?, ?, ?, 'confirmed') RETURNING id
            """, Long.class, org.id(), classId, memberId);
    }

    private String bookingStatus(long id) {
        return jdbc.queryForObject("SELECT status::text FROM class_bookings WHERE id = ?", String.class, id);
    }

    private int auditCount(String type, long membershipId) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM audit_events WHERE event_type = ? AND entity_type = 'membership' AND entity_id = ?
            """, Integer.class, type, membershipId);
    }

    @Test
    void suspenderExigeMotivoCancelaReservasFuturasYSePuedeReactivar() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        Client c = client(org.id());
        long plan = plan(org.id(), 30, true);
        LocalDate today = today(org.id());
        long id = insert(org, c.memberId(), plan, today.minusDays(2), today.plusDays(27), "active");
        long booking = futureBooking(org, c.memberId());

        assertThat(status(patchJ(staff, "/memberships/" + id + "/suspend", "{}"))).isEqualTo(400);
        assertThat(status(patchJ(staff, "/memberships/" + id + "/suspend", "{\"reason\":\"  \"}"))).isEqualTo(400);

        MvcResult suspended = patchJ(staff, "/memberships/" + id + "/suspend", "{\"reason\":\"Viaje\"}");
        assertThat(status(suspended)).isEqualTo(200);
        assertThat(body(suspended)).contains("\"status\":\"suspended\"").contains("\"statusReason\":\"Viaje\"");
        assertThat(bookingStatus(booking)).isEqualTo("cancelled");
        assertThat(auditCount("MEMBERSHIP_SUSPENDED", id)).isEqualTo(1);
        assertThat(status(patchJ(staff, "/memberships/" + id + "/suspend", "{\"reason\":\"Otra vez\"}")))
            .isEqualTo(409);

        MvcResult back = patchJ(staff, "/memberships/" + id + "/reactivate", "{\"reason\":\"Regresó\"}");
        assertThat(status(back)).isEqualTo(200);
        assertThat(body(back)).contains("\"status\":\"active\"").contains("\"endDate\":\"" + today.plusDays(27) + "\"");
        assertThat(auditCount("MEMBERSHIP_REACTIVATED", id)).isEqualTo(1);
    }

    @Test
    void noSeReactivaUnaSuspendidaQueYaVencio() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long plan = plan(org.id(), 30, true);
        LocalDate today = today(org.id());
        long id = insert(org, client(org.id()).memberId(), plan, today.minusDays(40), today.minusDays(1), "suspended");

        MvcResult r = patchJ(admin, "/memberships/" + id + "/reactivate", "{\"reason\":\"Intento\"}");

        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("MEMBERSHIP_EXPIRED");
    }

    @Test
    void cancelarEsDefinitivoGuardaMotivoYAuditaYCancelaReservas() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        Client c = client(org.id());
        long plan = plan(org.id(), 30, true);
        LocalDate today = today(org.id());
        long id = insert(org, c.memberId(), plan, today, today.plusDays(29), "active");
        long booking = futureBooking(org, c.memberId());

        assertThat(status(patchJ(admin, "/memberships/" + id + "/cancel", "{}"))).isEqualTo(400);
        MvcResult cancelled = patchJ(admin, "/memberships/" + id + "/cancel", "{\"reason\":\"Error de registro\"}");

        assertThat(status(cancelled)).isEqualTo(200);
        assertThat(body(cancelled)).contains("\"status\":\"cancelled\"").contains("\"effectiveStatus\":\"cancelled\"");
        assertThat(bookingStatus(booking)).isEqualTo("cancelled");
        assertThat(auditCount("MEMBERSHIP_CANCELLED", id)).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT status_reason, status_changed_by_member_id FROM memberships WHERE id = ?", id);
        assertThat(row.get("status_reason")).isEqualTo("Error de registro");
        assertThat(((Number) row.get("status_changed_by_member_id")).longValue()).isEqualTo(org.adminMemberId());
        assertThat(status(patchJ(admin, "/memberships/" + id + "/reactivate", "{\"reason\":\"No\"}"))).isEqualTo(409);
        assertThat(status(patchJ(admin, "/memberships/" + id + "/cancel", "{\"reason\":\"Otra\"}"))).isEqualTo(409);
    }

    @Test
    void unaMembresiaDeOtroGimnasioEsIndistinguibleDeUnaInexistente() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        long foreignPlan = plan(other.id(), 30, true);
        LocalDate today = today(other.id());
        long foreign = insert(other, client(other.id()).memberId(), foreignPlan, today, today.plusDays(29), "active");
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        assertThat(status(patchJ(staff, "/memberships/" + foreign + "/suspend", "{\"reason\":\"x\"}"))).isEqualTo(404);
        assertThat(status(patchJ(staff, "/memberships/" + foreign + "/cancel", "{\"reason\":\"x\"}"))).isEqualTo(404);
        assertThat(status(postJ(staff, "/memberships/" + foreign + "/renew", "{}"))).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT status::text FROM memberships WHERE id = ?", String.class, foreign))
            .isEqualTo("active");
    }
}

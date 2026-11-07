package com.nexfit.attendance;

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
import java.util.concurrent.atomic.AtomicInteger;
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

/** NEX-59 (US-36), NEX-72 (US-37) y NEX-73 (US-38): ingreso, salida e historial de asistencia. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AttendanceTests {

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
    private static final AtomicInteger SEQ = new AtomicInteger();

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

    private long plan(long orgId) {
        return jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, ?, 50000, 30, true) RETURNING id
            """, Long.class, orgId, "Plan " + SEQ.incrementAndGet());
    }

    /** Cliente con una membresía vigente desde hace diez días y hasta dentro de sesenta. */
    private Client memberWithMembership(Org org, long planId) {
        Client c = client(org.id());
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status)
            VALUES (?, ?, ?, ?, CURRENT_DATE - 10, CURRENT_DATE + 60, 'active')
            """, org.id(), c.memberId(), planId, org.adminMemberId());
        return c;
    }

    private void suspend(Org org, long memberId) {
        // ck_memberships_status_reason: un estado distinto de active exige motivo, fecha y autor.
        jdbc.update("""
            UPDATE memberships SET status = 'suspended', status_reason = 'prueba', status_changed_at = now(),
                                   status_changed_by_member_id = ?
             WHERE member_id = ?""", org.adminMemberId(), memberId);
    }

    private MvcResult send(Cookie session, String method, String uri, String json) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(uri);
            case "PATCH" -> patch(uri);
            default -> get(uri);
        };
        if (!method.equals("GET")) {
            builder = builder.with(csrf()).contentType(MediaType.APPLICATION_JSON);
            if (json != null) {
                builder = builder.content(json);
            }
        }
        return mvc.perform(builder.cookie(session)).andReturn();
    }

    private MvcResult checkIn(Cookie session, long branchId, Long memberId) throws Exception {
        String body = memberId == null ? "{\"branchId\":%d}".formatted(branchId)
            : "{\"branchId\":%d,\"memberId\":%d}".formatted(branchId, memberId);
        return send(session, "POST", "/check-ins", body);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static long idOf(String json) {
        return Long.parseLong(json.replaceAll("(?s)^\\{\"id\":(\\d+).*", "$1"));
    }

    private int rows(long memberId) {
        return jdbc.queryForObject("SELECT count(*) FROM check_ins WHERE member_id = ?", Integer.class, memberId);
    }

    /** Inserta un ingreso abierto de hace {@code days} días (la membresía de la fixture ya cubría ese día). */
    private long openCheckInDaysAgo(Org org, long memberId, long branchId, int days) {
        return jdbc.queryForObject("""
            INSERT INTO check_ins (organization_id, member_id, branch_id, check_in_at, auto_closed)
            VALUES (?, ?, ?, now() - make_interval(days => ?), false) RETURNING id
            """, Long.class, org.id(), memberId, branchId, days);
    }

    // ---- US-36 · registrar ingreso -------------------------------------------------------

    @Test
    void elClienteConMembresiaVigenteRegistraSuIngreso() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        long branch = fx.branch(org.id());

        MvcResult r = checkIn(fx.login(c.account()), branch, null);

        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"memberId\":" + c.memberId()).contains("\"branchId\":" + branch)
            .contains("\"checkOutAt\":null").contains("\"autoClosed\":false");
        assertThat(rows(c.memberId())).isEqualTo(1);
    }

    @Test
    void sinMembresiaVigenteSeRechazaConElMotivo() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        long branch = fx.branch(org.id());
        Client none = client(org.id());
        Client expired = client(org.id());
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status)
            VALUES (?, ?, ?, ?, CURRENT_DATE - 60, CURRENT_DATE - 30, 'active')
            """, org.id(), expired.memberId(), plan, org.adminMemberId());
        Client suspended = memberWithMembership(org, plan);
        suspend(org, suspended.memberId());

        MvcResult a = checkIn(fx.login(none.account()), branch, null);
        MvcResult b = checkIn(fx.login(expired.account()), branch, null);
        MvcResult c = checkIn(fx.login(suspended.account()), branch, null);

        assertThat(status(a)).isEqualTo(409);
        assertThat(body(a)).contains("NO_VALID_MEMBERSHIP").contains("no tiene membresía");
        assertThat(status(b)).isEqualTo(409);
        assertThat(body(b)).contains("NO_VALID_MEMBERSHIP").contains("vencida");
        assertThat(status(c)).isEqualTo(409);
        assertThat(body(c)).contains("NO_VALID_MEMBERSHIP").contains("suspendida");
        assertThat(rows(none.memberId()) + rows(expired.memberId()) + rows(suspended.memberId())).isZero();
    }

    @Test
    void unSegundoIngresoElMismoDiaSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        long branch = fx.branch(org.id());
        Cookie session = fx.login(c.account());

        assertThat(status(checkIn(session, branch, null))).isEqualTo(201);
        MvcResult again = checkIn(session, branch, null);

        assertThat(status(again)).isEqualTo(409);
        assertThat(body(again)).contains("ALREADY_CHECKED_IN");
        assertThat(rows(c.memberId())).isEqualTo(1);
    }

    @Test
    void unIngresoOlvidadoDeUnDiaAnteriorSeCierraSoloYElNuevoSeAcepta() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        long branch = fx.branch(org.id());
        long old = openCheckInDaysAgo(org, c.memberId(), branch, 2);

        MvcResult r = checkIn(fx.login(c.account()), branch, null);

        assertThat(status(r)).isEqualTo(201);
        assertThat(idOf(body(r))).isNotEqualTo(old);
        Boolean auto = jdbc.queryForObject("SELECT auto_closed FROM check_ins WHERE id = ?", Boolean.class, old);
        Boolean closedAfterIn = jdbc.queryForObject(
            "SELECT check_out_at IS NOT NULL AND check_out_at > check_in_at AND check_out_at < now() FROM check_ins "
                + "WHERE id = ?", Boolean.class, old);
        assertThat(auto).isTrue();
        assertThat(closedAfterIn).isTrue();
        // El cierre cae dentro del mismo día local del ingreso olvidado.
        Boolean sameLocalDay = jdbc.queryForObject("""
            SELECT fn_org_local_date(organization_id, check_in_at) = fn_org_local_date(organization_id, check_out_at)
              FROM check_ins WHERE id = ?""", Boolean.class, old);
        assertThat(sameLocalDay).isTrue();
        assertThat(rows(c.memberId())).isEqualTo(2);
    }

    @Test
    void elStaffRegistraElIngresoDeUnClienteYDebeIndicarlo() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        long branch = fx.branch(org.id());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        MvcResult missing = checkIn(staff, branch, null);
        MvcResult ok = checkIn(staff, branch, c.memberId());

        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing)).contains("VALIDATION_ERROR").contains("memberId");
        assertThat(status(ok)).isEqualTo(201);
        Long registeredBy = jdbc.queryForObject("SELECT registered_by_member_id FROM check_ins WHERE id = ?",
            Long.class, idOf(body(ok)));
        assertThat(registeredBy).isNotNull().isNotEqualTo(c.memberId());
    }

    @Test
    void unClienteNoRegistraElIngresoDeOtro() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client a = memberWithMembership(org, plan);
        Client b = memberWithMembership(org, plan);
        long branch = fx.branch(org.id());

        MvcResult r = checkIn(fx.login(a.account()), branch, b.memberId());

        assertThat(status(r)).isEqualTo(403);
        assertThat(rows(b.memberId())).isZero();
    }

    @Test
    void laSedeOElClienteDeOtroGimnasioSeRechazan() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        Client foreign = memberWithMembership(other, plan(other.id()));
        long foreignBranch = fx.branch(other.id());
        long ownBranch = fx.branch(org.id());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        MvcResult wrongBranch = checkIn(fx.login(c.account()), foreignBranch, null);
        MvcResult wrongClient = checkIn(staff, ownBranch, foreign.memberId());
        MvcResult noBranch = send(fx.login(c.account()), "POST", "/check-ins", "{}");

        assertThat(status(wrongBranch)).isEqualTo(400);
        assertThat(status(wrongClient)).isEqualTo(400);
        assertThat(status(noBranch)).isEqualTo(400);
        assertThat(rows(foreign.memberId())).isZero();
    }

    // ---- US-37 · registrar salida --------------------------------------------------------

    @Test
    void elClienteRegistraSuSalidaUnaSolaVez() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        long branch = fx.branch(org.id());
        Cookie session = fx.login(c.account());
        long id = idOf(body(checkIn(session, branch, null)));

        MvcResult out = send(session, "PATCH", "/check-ins/" + id + "/check-out", null);
        MvcResult again = send(session, "PATCH", "/check-ins/" + id + "/check-out", null);

        assertThat(status(out)).isEqualTo(200);
        assertThat(body(out)).doesNotContain("\"checkOutAt\":null").contains("\"autoClosed\":false");
        assertThat(status(again)).isEqualTo(409);
        assertThat(body(again)).contains("ALREADY_CHECKED_OUT");
    }

    @Test
    void despuesDeLaSalidaElClientePuedeEntrarDeNuevo() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        long branch = fx.branch(org.id());
        Cookie session = fx.login(c.account());
        long id = idOf(body(checkIn(session, branch, null)));
        send(session, "PATCH", "/check-ins/" + id + "/check-out", null);

        // Una salida cierra el ingreso; la regla de un solo ingreso abierto por día aplica solo mientras siga abierto.
        MvcResult second = checkIn(session, branch, null);

        assertThat(status(second)).isEqualTo(201);
        assertThat(rows(c.memberId())).isEqualTo(2);
    }

    @Test
    void laSalidaFuncionaAunqueLaMembresiaSeHayaSuspendidoDentro() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        long branch = fx.branch(org.id());
        Cookie session = fx.login(c.account());
        long id = idOf(body(checkIn(session, branch, null)));
        suspend(org, c.memberId());

        MvcResult out = send(session, "PATCH", "/check-ins/" + id + "/check-out", null);

        assertThat(status(out)).isEqualTo(200);
    }

    @Test
    void unClienteNoCierraElIngresoDeOtroYElStaffSi() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client a = memberWithMembership(org, plan);
        Client b = memberWithMembership(org, plan);
        long branch = fx.branch(org.id());
        long id = idOf(body(checkIn(fx.login(a.account()), branch, null)));

        MvcResult byOther = send(fx.login(b.account()), "PATCH", "/check-ins/" + id + "/check-out", null);
        MvcResult byStaff = send(fx.login(fx.actor(org.id(), "staff")), "PATCH", "/check-ins/" + id + "/check-out",
            null);

        assertThat(status(byOther)).isEqualTo(403);
        assertThat(status(byStaff)).isEqualTo(200);
    }

    @Test
    void cerrarUnIngresoDeOtroGimnasioDa404() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Client foreign = memberWithMembership(other, plan(other.id()));
        long id = idOf(body(checkIn(fx.login(foreign.account()), fx.branch(other.id()), null)));
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        MvcResult r = send(staff, "PATCH", "/check-ins/" + id + "/check-out", null);

        assertThat(status(r)).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT check_out_at IS NULL FROM check_ins WHERE id = ?", Boolean.class, id))
            .isTrue();
    }

    // ---- historial ------------------------------------------------------------------------

    @Test
    void elClienteSoloVeSuHistorialYElStaffFiltraPorCliente() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client a = memberWithMembership(org, plan);
        Client b = memberWithMembership(org, plan);
        long branch = fx.branch(org.id());
        checkIn(fx.login(a.account()), branch, null);
        checkIn(fx.login(b.account()), branch, null);

        String own = body(send(fx.login(a.account()), "GET", "/check-ins?memberId=" + b.memberId(), null));
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        String all = body(send(staff, "GET", "/check-ins", null));
        String onlyB = body(send(staff, "GET", "/check-ins?memberId=" + b.memberId(), null));

        assertThat(own).contains("\"memberId\":" + a.memberId()).doesNotContain("\"memberId\":" + b.memberId());
        assertThat(all).contains("\"memberId\":" + a.memberId()).contains("\"memberId\":" + b.memberId());
        assertThat(onlyB).contains("\"memberId\":" + b.memberId()).doesNotContain("\"memberId\":" + a.memberId());
    }

    @Test
    void elHistorialSeFiltraPorFechasYPorQuienSigueDentro() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client inside = memberWithMembership(org, plan);
        Client left = memberWithMembership(org, plan);
        long branch = fx.branch(org.id());
        checkIn(fx.login(inside.account()), branch, null);
        Cookie leftSession = fx.login(left.account());
        long leftId = idOf(body(checkIn(leftSession, branch, null)));
        send(leftSession, "PATCH", "/check-ins/" + leftId + "/check-out", null);
        Cookie admin = fx.login(org.admin());

        String open = body(send(admin, "GET", "/check-ins?open=true", null));
        String future = body(send(admin, "GET", "/check-ins?from=2999-01-01", null));
        String past = body(send(admin, "GET", "/check-ins?to=2000-01-01", null));
        String all = body(send(admin, "GET", "/check-ins?from=2000-01-01&to=2999-01-01", null));
        MvcResult bad = send(admin, "GET", "/check-ins?from=ayer", null);

        assertThat(open).contains("\"memberId\":" + inside.memberId()).doesNotContain("\"memberId\":" + left.memberId());
        assertThat(future).isEqualTo("[]");
        assertThat(past).isEqualTo("[]");
        assertThat(all).contains("\"memberId\":" + inside.memberId()).contains("\"memberId\":" + left.memberId());
        assertThat(status(bad)).isEqualTo(400);
    }

    @Test
    void elEntrenadorSoloVeLaAsistenciaDeSusAlumnos() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client mine = memberWithMembership(org, plan);
        Client notMine = memberWithMembership(org, plan);
        long branch = fx.branch(org.id());
        checkIn(fx.login(mine.account()), branch, null);
        checkIn(fx.login(notMine.account()), branch, null);
        Account trainer = fx.user(false, true);
        long trainerMember = fx.member(trainer, org.id(), "trainer", true);
        jdbc.update("INSERT INTO trainer_profiles (member_id, organization_id) VALUES (?, ?)", trainerMember,
            org.id());
        jdbc.update("""
            INSERT INTO trainer_clients (trainer_member_id, client_member_id, organization_id, is_active)
            VALUES (?, ?, ?, true)""", trainerMember, mine.memberId(), org.id());

        String json = body(send(fx.login(trainer), "GET", "/check-ins", null));

        assertThat(json).contains("\"memberId\":" + mine.memberId()).doesNotContain("\"memberId\":" + notMine.memberId());
    }

    @Test
    void elHistorialNoMuestraIngresosDeOtroGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Client foreign = memberWithMembership(other, plan(other.id()));
        checkIn(fx.login(foreign.account()), fx.branch(other.id()), null);

        String json = body(send(fx.login(org.admin()), "GET", "/check-ins", null));

        assertThat(json).doesNotContain("\"memberId\":" + foreign.memberId());
    }
}

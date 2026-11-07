package com.nexfit.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

/** NEX-74 (US-45) y NEX-62 (US-46): panel del Administrador y cifras globales del Master. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DashboardTests {

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

    private long plan(long orgId) {
        return jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, ?, 50000, 30, true) RETURNING id
            """, Long.class, orgId, "Plan " + SEQ.incrementAndGet());
    }

    private long clientMember(long orgId) {
        Account a = fx.user(false, true);
        return fx.member(a, orgId, "client", true);
    }

    private void membership(Org org, long memberId, long plan, LocalDate start, LocalDate end) {
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status)
            VALUES (?, ?, ?, ?, ?, ?, 'active')
            """, org.id(), memberId, plan, org.adminMemberId(), start, end);
    }

    /** {@code paidAtSql} es una expresión SQL constante de la prueba (por ejemplo {@code now()}). */
    private void payment(Org org, long memberId, String status, String amount, String paidAtSql) {
        jdbc.update("""
            INSERT INTO payments (organization_id, member_id, registered_by_member_id, concept, amount, paid_at, method,
                                  status, void_reason, completed_at, completed_by_member_id)
            VALUES (?, ?, ?, 'Mensualidad', CAST(? AS numeric), %s, 'cash', CAST(? AS payment_status),
                    CASE WHEN ? = 'voided' THEN 'Error de digitación' END,
                    CASE WHEN ? = 'completed' THEN now() END,
                    CASE WHEN ? = 'completed' THEN CAST(? AS bigint) END)
            """.formatted(paidAtSql), org.id(), memberId, org.adminMemberId(), amount, status, status, status, status,
            org.adminMemberId());
    }

    /**
     * {@code startsAtSql} es una expresión SQL constante de la prueba (por ejemplo {@code now() + interval '2 days'}).
     * Una clase solo se inserta como programada; para cancelarla se actualiza después (trg_class_validate).
     */
    private long gymClass(Org org, long branch, String startsAtSql, int capacity, String status) {
        long id = jdbc.queryForObject("""
            INSERT INTO classes (organization_id, branch_id, name, starts_at, duration_minutes, capacity, status)
            VALUES (?, ?, 'Spinning', %s, 60, ?, 'scheduled') RETURNING id
            """.formatted(startsAtSql), Long.class, org.id(), branch, capacity);
        if (status.equals("cancelled")) {
            jdbc.update("UPDATE classes SET status = 'cancelled', cancelled_at = now() WHERE id = ?", id);
        }
        return id;
    }

    private void book(Org org, long classId, long memberId) {
        jdbc.update("INSERT INTO class_bookings (organization_id, class_id, member_id, status) "
            + "VALUES (?, ?, ?, 'confirmed')", org.id(), classId, memberId);
    }

    private MvcResult getJ(Cookie session, String uri) throws Exception {
        return mvc.perform(get(uri).cookie(session)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static long number(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        assertThat(m.find()).as("campo " + field + " en " + json).isTrue();
        return Long.parseLong(m.group(1));
    }

    private LocalDate today(Org org) {
        return jdbc.queryForObject("SELECT fn_org_local_date(?, now())", LocalDate.class, org.id());
    }

    // ---- US-45 · panel del Administrador -------------------------------------------------

    @Test
    void elPanelCuentaClientesMembresiasPagosAsistenciaYOcupacion() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        long branch = fx.branch(org.id());
        LocalDate today = today(org);
        long expiring = clientMember(org.id());
        long steady = clientMember(org.id());
        long expired = clientMember(org.id());
        long inactive = fx.member(fx.user(false, true), org.id(), "client", false);
        membership(org, expiring, plan, today.minusDays(20), today.plusDays(3));
        membership(org, steady, plan, today.minusDays(5), today.plusDays(30));
        membership(org, expired, plan, today.minusDays(60), today.minusDays(5));

        payment(org, steady, "completed", "100000", "now()");
        payment(org, steady, "completed", "50000", "now()");
        payment(org, steady, "pending", "999999", "now()");
        payment(org, steady, "voided", "888888", "now()");
        // Un pago completado del mes anterior no cuenta en el mes actual.
        payment(org, steady, "completed", "777777", "now() - interval '40 days'");

        jdbc.update("""
            INSERT INTO check_ins (organization_id, member_id, branch_id, check_in_at, auto_closed)
            VALUES (?, ?, ?, now(), false)""", org.id(), steady, branch);
        jdbc.update("""
            INSERT INTO check_ins (organization_id, member_id, branch_id, check_in_at, check_out_at, auto_closed)
            VALUES (?, ?, ?, now() - interval '3 days', now() - interval '3 days' + interval '1 hour', false)""",
            org.id(), expiring, branch);

        long inRange = gymClass(org, branch, "now() + interval '2 days'", 10, "scheduled");
        gymClass(org, branch, "now() + interval '10 days'", 50, "scheduled");
        gymClass(org, branch, "now() + interval '3 days'", 40, "cancelled");
        book(org, inRange, steady);
        book(org, inRange, expiring);

        String json = body(getJ(fx.login(org.admin()), "/dashboard/gym"));

        assertThat(number(json, "activeClients")).isEqualTo(3);
        assertThat(number(json, "currentMemberships")).isEqualTo(2);
        assertThat(number(json, "expiringMemberships")).isEqualTo(1);
        assertThat(number(json, "expiringInDays")).isEqualTo(7);
        assertThat(json).containsPattern("\"monthRevenue\":150000(\\.0+)?[,}]");
        assertThat(number(json, "todayCheckIns")).isEqualTo(1);
        assertThat(number(json, "classes")).isEqualTo(1);
        assertThat(number(json, "confirmedBookings")).isEqualTo(2);
        assertThat(number(json, "capacity")).isEqualTo(10);
        assertThat(json).containsPattern("\"percent\":20(\\.0+)?[,}]");
        assertThat(inactive).isPositive();
    }

    @Test
    void sinDatosTodoEsCeroYLaOcupacionNoDivideEntreCero() throws Exception {
        Org org = fx.orgWithAdmin();

        String json = body(getJ(fx.login(org.admin()), "/dashboard/gym"));

        assertThat(number(json, "activeClients")).isZero();
        assertThat(number(json, "currentMemberships")).isZero();
        assertThat(number(json, "expiringMemberships")).isZero();
        assertThat(number(json, "todayCheckIns")).isZero();
        assertThat(number(json, "capacity")).isZero();
        assertThat(json).containsPattern("\"monthRevenue\":0(\\.0+)?[,}]").containsPattern("\"percent\":0(\\.0+)?[,}]");
    }

    @Test
    void elPanelSoloUsaLosDatosDelGimnasioActivo() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        long plan = plan(other.id());
        long branch = fx.branch(other.id());
        long member = clientMember(other.id());
        membership(other, member, plan, today(other).minusDays(1), today(other).plusDays(3));
        payment(other, member, "completed", "400000", "now()");
        jdbc.update("""
            INSERT INTO check_ins (organization_id, member_id, branch_id, check_in_at, auto_closed)
            VALUES (?, ?, ?, now(), false)""", other.id(), member, branch);
        long cls = gymClass(other, branch, "now() + interval '1 day'", 8, "scheduled");
        book(other, cls, member);

        String json = body(getJ(fx.login(org.admin()), "/dashboard/gym"));

        assertThat(number(json, "activeClients")).isZero();
        assertThat(number(json, "currentMemberships")).isZero();
        assertThat(number(json, "todayCheckIns")).isZero();
        assertThat(number(json, "classes")).isZero();
        assertThat(json).doesNotContain("400000");
    }

    @Test
    void soloElAdministradorVeElPanelDelGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();

        for (String role : new String[] {"staff", "trainer", "client"}) {
            assertThat(getJ(fx.login(fx.actor(org.id(), role)), "/dashboard/gym").getResponse().getStatus())
                .as(role).isEqualTo(403);
        }
        assertThat(getJ(fx.login(fx.user(true, true)), "/dashboard/gym").getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/dashboard/gym")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(getJ(fx.login(org.admin()), "/dashboard/gym").getResponse().getStatus()).isEqualTo(200);
    }

    // ---- US-46 · cifras globales del Master ----------------------------------------------

    @Test
    void elMasterVeCifrasAgregadasQueReflejanElEstadoVigente() throws Exception {
        Cookie master = fx.login(fx.user(true, true));
        String before = body(getJ(master, "/dashboard/platform"));

        Org org = fx.orgWithAdmin();
        fx.branch(org.id());
        fx.branch(org.id());
        long plan = plan(org.id());
        long a = clientMember(org.id());
        long b = clientMember(org.id());
        clientMember(org.id());
        membership(org, a, plan, today(org).minusDays(1), today(org).plusDays(10));
        membership(org, b, plan, today(org).minusDays(40), today(org).minusDays(10));
        Org inactiveOrg = fx.orgWithAdmin();
        jdbc.update("UPDATE organizations SET is_active = false WHERE id = ?", inactiveOrg.id());

        String after = body(getJ(master, "/dashboard/platform"));

        assertThat(number(after, "gyms") - number(before, "gyms")).isEqualTo(1);
        assertThat(number(after, "branches") - number(before, "branches")).isEqualTo(2);
        assertThat(number(after, "clients") - number(before, "clients")).isEqualTo(3);
        assertThat(number(after, "currentMemberships") - number(before, "currentMemberships")).isEqualTo(1);
        // Solo cifras: ninguna persona ni documento.
        assertThat(after).doesNotContain("email").doesNotContain("@").doesNotContain("Pérez");
    }

    @Test
    void soloElMasterVeLasCifrasGlobales() throws Exception {
        Org org = fx.orgWithAdmin();

        assertThat(getJ(fx.login(org.admin()), "/dashboard/platform").getResponse().getStatus()).isEqualTo(403);
        for (String role : new String[] {"staff", "trainer", "client"}) {
            assertThat(getJ(fx.login(fx.actor(org.id(), role)), "/dashboard/platform").getResponse().getStatus())
                .as(role).isEqualTo(403);
        }
        assertThat(mvc.perform(get("/dashboard/platform")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(getJ(fx.login(fx.user(true, true)), "/dashboard/platform").getResponse().getStatus())
            .isEqualTo(200);
    }
}

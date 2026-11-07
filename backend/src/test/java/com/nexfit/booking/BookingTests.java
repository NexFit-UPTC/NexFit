package com.nexfit.booking;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** NEX-58 (US-32), NEX-69 (US-33), NEX-70 (US-34) y NEX-68 (US-30): reservar, cancelar, consultar y cancelar clases. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class BookingTests {

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

    /** Cliente con una membresía vigente durante los próximos 60 días. */
    private Client memberWithMembership(Org org, long planId) {
        Client c = client(org.id());
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status)
            VALUES (?, ?, ?, ?, CURRENT_DATE - 1, CURRENT_DATE + 60, 'active')
            """, org.id(), c.memberId(), planId, org.adminMemberId());
        return c;
    }

    private long plan(long orgId) {
        return jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, ?, 50000, 30, true) RETURNING id
            """, Long.class, orgId, "Plan " + SEQ.incrementAndGet());
    }

    private long gymClass(Org org, String startsAt, int capacity) {
        long branch = fx.branch(org.id());
        return jdbc.queryForObject("""
            INSERT INTO classes (organization_id, branch_id, name, starts_at, duration_minutes, capacity, status)
            VALUES (?, ?, 'Spinning', CAST(? AS timestamptz), 60, ?, 'scheduled') RETURNING id
            """, Long.class, org.id(), branch, startsAt, capacity);
    }

    private long futureClass(Org org, int capacity) {
        return jdbc.queryForObject("""
            INSERT INTO classes (organization_id, branch_id, name, starts_at, duration_minutes, capacity, status)
            VALUES (?, ?, 'Spinning', now() + interval '2 days', 60, ?, 'scheduled') RETURNING id
            """, Long.class, org.id(), fx.branch(org.id()), capacity);
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

    private MvcResult book(Cookie session, long classId, Long memberId) throws Exception {
        String body = memberId == null ? "{\"classId\":%d}".formatted(classId)
            : "{\"classId\":%d,\"memberId\":%d}".formatted(classId, memberId);
        return send(session, "POST", "/bookings", body);
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

    private int confirmed(long classId) {
        return jdbc.queryForObject("SELECT count(*) FROM class_bookings WHERE class_id = ? AND status = 'confirmed'",
            Integer.class, classId);
    }

    // ---- US-32 · reservar ----------------------------------------------------------------

    @Test
    void elClienteConMembresiaVigenteReservaParaSiMismo() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        long cls = futureClass(org, 5);

        MvcResult r = book(fx.login(c.account()), cls, null);

        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"status\":\"confirmed\"").contains("\"classId\":" + cls);
        assertThat(confirmed(cls)).isEqualTo(1);
    }

    @Test
    void elStaffReservaANombreDelClienteYElClienteNoPuedeReservarPorOtro() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client c = memberWithMembership(org, plan);
        Client other = memberWithMembership(org, plan);
        long cls = futureClass(org, 5);
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        assertThat(status(book(staff, cls, null))).isEqualTo(400);
        assertThat(status(book(staff, cls, c.memberId()))).isEqualTo(201);
        assertThat(status(book(fx.login(other.account()), cls, c.memberId()))).isEqualTo(403);
        assertThat(confirmed(cls)).isEqualTo(1);
    }

    @Test
    void sinMembresiaOConMembresiaQueVenceAntesDeLaClaseSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client none = client(org.id());
        Client shortLived = client(org.id());
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status)
            VALUES (?, ?, ?, ?, CURRENT_DATE - 5, CURRENT_DATE, 'active')
            """, org.id(), shortLived.memberId(), plan, org.adminMemberId());
        long cls = futureClass(org, 5);

        MvcResult a = book(fx.login(none.account()), cls, null);
        MvcResult b = book(fx.login(shortLived.account()), cls, null);

        assertThat(status(a)).isEqualTo(409);
        assertThat(body(a)).contains("NO_VALID_MEMBERSHIP");
        assertThat(status(b)).isEqualTo(409);
        assertThat(body(b)).contains("NO_VALID_MEMBERSHIP");
        assertThat(confirmed(cls)).isZero();
    }

    @Test
    void unaClaseCanceladaOYaIniciadaNoAdmiteReservas() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        Cookie session = fx.login(c.account());
        long cancelled = futureClass(org, 5);
        jdbc.update("UPDATE classes SET status = 'cancelled', cancelled_at = now() WHERE id = ?", cancelled);
        long started = gymClass(org, "2020-01-01T10:00:00Z", 5);

        MvcResult a = book(session, cancelled, null);
        MvcResult b = book(session, started, null);

        assertThat(status(a)).isEqualTo(409);
        assertThat(body(a)).contains("CLASS_CANCELLED");
        assertThat(status(b)).isEqualTo(409);
        assertThat(body(b)).contains("CLASS_STARTED");
    }

    @Test
    void noSePuedeReservarDosVecesYTrasCancelarSePuedeDeNuevo() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        Cookie session = fx.login(c.account());
        long cls = futureClass(org, 5);

        MvcResult first = book(session, cls, null);
        MvcResult again = book(session, cls, null);
        assertThat(status(again)).isEqualTo(409);
        assertThat(body(again)).contains("ALREADY_BOOKED");

        assertThat(status(send(session, "PATCH", "/bookings/" + idOf(body(first)) + "/cancel", null))).isEqualTo(200);
        assertThat(status(book(session, cls, null))).isEqualTo(201);
        assertThat(confirmed(cls)).isEqualTo(1);
    }

    @Test
    void sinCupoSeRechazaConMensajeClaro() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client first = memberWithMembership(org, plan);
        Client second = memberWithMembership(org, plan);
        long cls = futureClass(org, 1);

        assertThat(status(book(fx.login(first.account()), cls, null))).isEqualTo(201);
        MvcResult r = book(fx.login(second.account()), cls, null);

        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("CLASS_FULL");
        assertThat(confirmed(cls)).isEqualTo(1);
    }

    @Test
    void unaClaseOUnClienteDeOtroGimnasioSeRechazan() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Client mine = memberWithMembership(org, plan(org.id()));
        long foreignClass = futureClass(other, 5);
        long ownClass = futureClass(org, 5);
        Client foreignClient = memberWithMembership(other, plan(other.id()));
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        assertThat(status(book(fx.login(mine.account()), foreignClass, null))).isEqualTo(400);
        assertThat(status(book(staff, ownClass, foreignClient.memberId()))).isEqualTo(400);
        assertThat(confirmed(foreignClass)).isZero();
        assertThat(confirmed(ownClass)).isZero();
    }

    @Test
    void solicitudesSimultaneasPorElUltimoCupoDejanExactamenteUnaReserva() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        long cls = futureClass(org, 1);
        int contenders = 8;
        List<Cookie> sessions = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            sessions.add(fx.login(memberWithMembership(org, plan).account()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (Cookie session : sessions) {
            results.add(pool.submit(() -> {
                start.await();
                return status(book(session, cls, null));
            }));
        }
        start.countDown();
        int created = 0;
        int conflicts = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 201) {
                created++;
            } else if (s == 409) {
                conflicts++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(contenders - 1);
        assertThat(confirmed(cls)).isEqualTo(1);
    }

    // ---- US-33 · cancelar reserva --------------------------------------------------------

    @Test
    void unClienteNoCancelaLaReservaDeOtroPeroElStaffSi() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client owner = memberWithMembership(org, plan);
        Client other = memberWithMembership(org, plan);
        long cls = futureClass(org, 5);
        long booking = idOf(body(book(fx.login(owner.account()), cls, null)));

        assertThat(status(send(fx.login(other.account()), "PATCH", "/bookings/" + booking + "/cancel", null)))
            .isEqualTo(403);
        assertThat(confirmed(cls)).isEqualTo(1);
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        MvcResult r = send(staff, "PATCH", "/bookings/" + booking + "/cancel", null);
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"status\":\"cancelled\"");
        assertThat(confirmed(cls)).isZero();
        assertThat(status(send(staff, "PATCH", "/bookings/" + booking + "/cancel", null))).isEqualTo(409);
    }

    @Test
    void noSeCancelaUnaReservaDeUnaClaseQueYaInicio() throws Exception {
        Org org = fx.orgWithAdmin();
        Client c = memberWithMembership(org, plan(org.id()));
        long cls = futureClass(org, 5);
        long booking = idOf(body(book(fx.login(c.account()), cls, null)));
        // La clase empezó hace una hora (se mueve su inicio directamente: la API no lo permite con reservas).
        jdbc.update("UPDATE classes SET starts_at = now() - interval '1 hour' WHERE id = ?", cls);

        MvcResult r = send(fx.login(c.account()), "PATCH", "/bookings/" + booking + "/cancel", null);

        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("CLASS_STARTED");
        assertThat(confirmed(cls)).isEqualTo(1);
    }

    // ---- US-34 · consultar ---------------------------------------------------------------

    @Test
    void elClienteVeSoloSusReservasIncluidasLasCanceladasYElPersonalFiltra() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client mine = memberWithMembership(org, plan);
        Client theirs = memberWithMembership(org, plan);
        long cls = futureClass(org, 5);
        Cookie client = fx.login(mine.account());
        long own = idOf(body(book(client, cls, null)));
        long foreign = idOf(body(book(fx.login(theirs.account()), cls, null)));
        send(client, "PATCH", "/bookings/" + own + "/cancel", null);

        String mineList = body(send(client, "GET", "/bookings", null));
        assertThat(mineList).contains("\"id\":" + own).contains("\"status\":\"cancelled\"")
            .doesNotContain("\"id\":" + foreign);
        assertThat(body(send(client, "GET", "/bookings?memberId=" + theirs.memberId(), null)))
            .doesNotContain("\"id\":" + foreign);

        Cookie admin = fx.login(org.admin());
        assertThat(body(send(admin, "GET", "/bookings?classId=" + cls + "&status=confirmed", null)))
            .contains("\"id\":" + foreign).doesNotContain("\"id\":" + own);
        assertThat(status(send(admin, "GET", "/bookings?status=x", null))).isEqualTo(400);
    }

    @Test
    void elEntrenadorVeSoloLasReservasDeSusClases() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Account trainer = fx.actor(org.id(), "trainer");
        long trainerMember = jdbc.queryForObject("SELECT id FROM organization_members WHERE user_id = ?",
            Long.class, trainer.id());
        long mine = futureClass(org, 5);
        long others = futureClass(org, 5);
        jdbc.update("UPDATE classes SET trainer_member_id = ? WHERE id = ?", trainerMember, mine);
        Client c = memberWithMembership(org, plan);
        long inMine = idOf(body(book(fx.login(c.account()), mine, null)));
        long inOthers = idOf(body(book(fx.login(c.account()), others, null)));

        String list = body(send(fx.login(trainer), "GET", "/bookings", null));

        assertThat(list).contains("\"id\":" + inMine).doesNotContain("\"id\":" + inOthers);
    }

    // ---- US-30 · cancelar clase ----------------------------------------------------------

    @Test
    void cancelarUnaClaseCancelaSusReservasYBloqueaNuevasYAudita() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        Client a = memberWithMembership(org, plan);
        Client b = memberWithMembership(org, plan);
        long cls = futureClass(org, 5);
        book(fx.login(a.account()), cls, null);
        book(fx.login(b.account()), cls, null);
        Cookie admin = fx.login(org.admin());

        MvcResult r = send(admin, "PATCH", "/classes/" + cls + "/cancel", "{\"reason\":\"Entrenador enfermo\"}");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"status\":\"cancelled\"");
        assertThat(confirmed(cls)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM class_bookings WHERE class_id = ? AND status = 'cancelled'",
            Integer.class, cls)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_events WHERE event_type = 'CLASS_CANCELLED' AND entity_id = ?
            """, Integer.class, cls)).isEqualTo(1);
        MvcResult late = book(fx.login(a.account()), cls, null);
        assertThat(status(late)).isEqualTo(409);
        assertThat(body(late)).contains("CLASS_CANCELLED");
        assertThat(status(send(admin, "PATCH", "/classes/" + cls + "/cancel", "{}"))).isEqualTo(409);
    }

    @Test
    void soloElAdministradorCancelaClasesYLasAjenasNoExisten() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        long cls = futureClass(org, 5);
        long foreign = futureClass(other, 5);

        assertThat(status(send(fx.login(fx.actor(org.id(), "staff")), "PATCH", "/classes/" + cls + "/cancel", "{}")))
            .isEqualTo(403);
        assertThat(status(send(fx.login(fx.actor(org.id(), "trainer")), "PATCH", "/classes/" + cls + "/cancel", "{}")))
            .isEqualTo(403);
        assertThat(status(send(fx.login(org.admin()), "PATCH", "/classes/" + foreign + "/cancel", "{}")))
            .isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT status::text FROM classes WHERE id = ?", String.class, cls))
            .isEqualTo("scheduled");
    }
}

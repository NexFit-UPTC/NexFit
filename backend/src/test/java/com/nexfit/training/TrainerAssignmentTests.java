package com.nexfit.training;

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

/** NEX-47 (US-39) y NEX-52 (US-40): asignar y retirar alumnos y consultarlos. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TrainerAssignmentTests {

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

    private record Person(Account account, long memberId) { }

    private Person client(long orgId) {
        Account a = fx.user(false, true);
        return new Person(a, fx.member(a, orgId, "client", true));
    }

    private Person trainer(long orgId) {
        Account a = fx.user(false, true);
        long member = fx.member(a, orgId, "trainer", true);
        jdbc.update("INSERT INTO trainer_profiles (member_id, organization_id, specialty) VALUES (?, ?, 'Fuerza')",
            member, orgId);
        return new Person(a, member);
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

    private MvcResult assign(Cookie session, long trainer, long client) throws Exception {
        return send(session, "POST", "/trainer-assignments",
            "{\"trainerMemberId\":%d,\"clientMemberId\":%d}".formatted(trainer, client));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private int rows(long trainer, long client) {
        return jdbc.queryForObject("SELECT count(*) FROM trainer_clients WHERE trainer_member_id = ? "
            + "AND client_member_id = ?", Integer.class, trainer, client);
    }

    private boolean active(long trainer, long client) {
        return jdbc.queryForObject("SELECT is_active FROM trainer_clients WHERE trainer_member_id = ? "
            + "AND client_member_id = ?", Boolean.class, trainer, client);
    }

    // ---- US-39 · asignar y retirar --------------------------------------------------------

    @Test
    void elStaffAsignaUnClienteAUnEntrenadorDelMismoGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        MvcResult r = assign(staff, t.memberId(), c.memberId());

        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"trainerMemberId\":" + t.memberId()).contains("\"clientMemberId\":" + c.memberId())
            .contains("\"active\":true");
        assertThat(active(t.memberId(), c.memberId())).isTrue();
        assertThat(status(assign(fx.login(org.admin()), t.memberId(), client(org.id()).memberId()))).isEqualTo(201);
    }

    @Test
    void sinEntrenadorDelGimnasioNiClienteNiAutoasignacionSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person foreignTrainer = trainer(other.id());
        Person foreignClient = client(other.id());
        Person c = client(org.id());
        Person notClient = new Person(null, fx.member(fx.user(false, true), org.id(), "staff", true));
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        MvcResult a = assign(staff, foreignTrainer.memberId(), c.memberId());
        MvcResult b = assign(staff, t.memberId(), foreignClient.memberId());
        MvcResult d = assign(staff, t.memberId(), notClient.memberId());
        MvcResult e = assign(staff, notClient.memberId(), c.memberId());
        MvcResult f = send(staff, "POST", "/trainer-assignments", "{\"trainerMemberId\":" + t.memberId() + "}");

        assertThat(status(a)).isEqualTo(400);
        assertThat(body(a)).contains("trainerMemberId");
        assertThat(status(b)).isEqualTo(400);
        assertThat(body(b)).contains("clientMemberId");
        assertThat(status(d)).isEqualTo(400);
        assertThat(status(e)).isEqualTo(400);
        assertThat(status(f)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trainer_clients WHERE organization_id IN (?, ?)",
            Integer.class, org.id(), other.id())).isZero();
    }

    @Test
    void unEntrenadorQueTambienEsClienteNoPuedeSerSuPropioAlumno() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = client(org.id());
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, 'trainer', ?)",
            t.memberId(), org.id());
        jdbc.update("INSERT INTO trainer_profiles (member_id, organization_id) VALUES (?, ?)", t.memberId(), org.id());

        MvcResult r = assign(fx.login(fx.actor(org.id(), "staff")), t.memberId(), t.memberId());

        assertThat(status(r)).isEqualTo(400);
        assertThat(rows(t.memberId(), t.memberId())).isZero();
    }

    @Test
    void retirarDejaLaRelacionInactivaYVolverAAsignarlaLaReactivaSinDuplicar() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        assign(staff, t.memberId(), c.memberId());

        MvcResult dup = assign(staff, t.memberId(), c.memberId());
        MvcResult retire = send(staff, "PATCH", "/trainer-assignments/%d/%d".formatted(t.memberId(), c.memberId()),
            "{\"active\":false}");
        MvcResult again = send(staff, "PATCH", "/trainer-assignments/%d/%d".formatted(t.memberId(), c.memberId()),
            "{\"active\":false}");

        assertThat(status(dup)).isEqualTo(409);
        assertThat(body(dup)).contains("ALREADY_ASSIGNED");
        assertThat(status(retire)).isEqualTo(200);
        assertThat(body(retire)).contains("\"active\":false");
        assertThat(status(again)).isEqualTo(200);
        assertThat(active(t.memberId(), c.memberId())).isFalse();

        MvcResult reassign = assign(staff, t.memberId(), c.memberId());

        assertThat(status(reassign)).isEqualTo(201);
        assertThat(active(t.memberId(), c.memberId())).isTrue();
        assertThat(rows(t.memberId(), c.memberId())).isEqualTo(1);
        assertThat(status(send(staff, "PATCH", "/trainer-assignments/%d/%d".formatted(t.memberId(), c.memberId()),
            "{\"active\":false}"))).isEqualTo(200);
        assertThat(status(send(staff, "PATCH", "/trainer-assignments/%d/%d".formatted(t.memberId(), c.memberId()),
            "{\"active\":true}"))).isEqualTo(200);
        assertThat(active(t.memberId(), c.memberId())).isTrue();
        assertThat(status(send(staff, "PATCH", "/trainer-assignments/%d/%d".formatted(t.memberId(), c.memberId()),
            "{}"))).isEqualTo(400);
    }

    @Test
    void unClientePuedeTenerMasDeUnEntrenador() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t1 = trainer(org.id());
        Person t2 = trainer(org.id());
        Person c = client(org.id());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        assertThat(status(assign(staff, t1.memberId(), c.memberId()))).isEqualTo(201);
        assertThat(status(assign(staff, t2.memberId(), c.memberId()))).isEqualTo(201);
    }

    @Test
    void entrenadorYClienteNoAsignanYElRetiroDeOtroGimnasioDa404() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        Person foreignT = trainer(other.id());
        Person foreignC = client(other.id());
        assign(fx.login(other.admin()), foreignT.memberId(), foreignC.memberId());

        assertThat(status(assign(fx.login(t.account()), t.memberId(), c.memberId()))).isEqualTo(403);
        assertThat(status(assign(fx.login(c.account()), t.memberId(), c.memberId()))).isEqualTo(403);
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        MvcResult r = send(staff, "PATCH", "/trainer-assignments/%d/%d".formatted(foreignT.memberId(),
            foreignC.memberId()), "{\"active\":false}");
        assertThat(status(r)).isEqualTo(404);
        assertThat(active(foreignT.memberId(), foreignC.memberId())).isTrue();
        assertThat(status(send(fx.login(t.account()), "PATCH", "/trainer-assignments/%d/%d".formatted(
            t.memberId(), c.memberId()), "{\"active\":false}"))).isEqualTo(403);
    }

    // ---- consultas ------------------------------------------------------------------------

    @Test
    void elEntrenadorVeSoloSusAsignacionesYElStaffTodas() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t1 = trainer(org.id());
        Person t2 = trainer(org.id());
        Person c1 = client(org.id());
        Person c2 = client(org.id());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        assign(staff, t1.memberId(), c1.memberId());
        assign(staff, t2.memberId(), c2.memberId());

        String own = body(send(fx.login(t1.account()), "GET", "/trainer-assignments?trainerMemberId=" + t2.memberId(),
            null));
        String all = body(send(staff, "GET", "/trainer-assignments", null));
        String byTrainer = body(send(staff, "GET", "/trainer-assignments?trainerMemberId=" + t2.memberId(), null));
        String trainersAsT1 = body(send(fx.login(t1.account()), "GET", "/trainers", null));
        String trainersAsStaff = body(send(staff, "GET", "/trainers", null));

        assertThat(own).contains("\"clientMemberId\":" + c1.memberId()).doesNotContain("\"clientMemberId\":" + c2.memberId());
        assertThat(all).contains("\"clientMemberId\":" + c1.memberId()).contains("\"clientMemberId\":" + c2.memberId());
        assertThat(byTrainer).contains("\"clientMemberId\":" + c2.memberId())
            .doesNotContain("\"clientMemberId\":" + c1.memberId());
        assertThat(trainersAsT1).contains("\"memberId\":" + t1.memberId()).doesNotContain("\"memberId\":" + t2.memberId());
        assertThat(trainersAsStaff).contains("\"memberId\":" + t1.memberId()).contains("\"memberId\":" + t2.memberId())
            .contains("\"activeStudents\":1").contains("\"specialty\":\"Fuerza\"");
        assertThat(status(send(fx.login(c1.account()), "GET", "/trainers", null))).isEqualTo(403);
        assertThat(status(send(fx.login(c1.account()), "GET", "/trainer-assignments", null))).isEqualTo(403);
    }

    @Test
    void misAlumnosMuestraSoloLosActivosConSusDatosYAudita() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person mine = client(org.id());
        Person retired = client(org.id());
        Person notMine = client(org.id());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        assign(staff, t.memberId(), mine.memberId());
        assign(staff, t.memberId(), retired.memberId());
        send(staff, "PATCH", "/trainer-assignments/%d/%d".formatted(t.memberId(), retired.memberId()),
            "{\"active\":false}");
        long branch = fx.branch(org.id());
        long plan = jdbc.queryForObject("INSERT INTO membership_plans (organization_id, name, price, duration_days, "
            + "is_active) VALUES (?, 'Plan alumnos', 1000, 30, true) RETURNING id", Long.class, org.id());
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status)
            VALUES (?, ?, ?, ?, CURRENT_DATE - 10, CURRENT_DATE + 30, 'active')""", org.id(), mine.memberId(), plan,
            org.adminMemberId());
        jdbc.update("INSERT INTO check_ins (organization_id, member_id, branch_id, check_in_at, check_out_at, "
            + "auto_closed) VALUES (?, ?, ?, now() - interval '2 days', now() - interval '2 days' + interval '1 hour', "
            + "false)", org.id(), mine.memberId(), branch);
        long before = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = "
            + "'HEALTH_CONTACT_DATA_ACCESSED' AND organization_id = ?", Long.class, org.id());

        MvcResult r = send(fx.login(t.account()), "GET", "/my-students", null);

        assertThat(status(r)).isEqualTo(200);
        String json = body(r);
        assertThat(json).contains("\"memberId\":" + mine.memberId()).contains("\"fullName\":\"Luis Pérez\"")
            .contains("\"relationship\":\"Padre\"").contains("\"phone\":\"3000000000\"")
            .contains("\"activeRoutines\":0").containsPattern("\"age\":3[0-9]").containsPattern("\"lastCheckIn\":\"20");
        assertThat(json).doesNotContain("\"memberId\":" + retired.memberId())
            .doesNotContain("\"memberId\":" + notMine.memberId());
        long after = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = "
            + "'HEALTH_CONTACT_DATA_ACCESSED' AND organization_id = ?", Long.class, org.id());
        assertThat(after - before).isEqualTo(1);
    }

    @Test
    void soloElEntrenadorUsaMisAlumnos() throws Exception {
        Org org = fx.orgWithAdmin();

        for (String role : new String[] {"staff", "client"}) {
            assertThat(status(send(fx.login(fx.actor(org.id(), role)), "GET", "/my-students", null))).as(role)
                .isEqualTo(403);
        }
        assertThat(status(send(fx.login(org.admin()), "GET", "/my-students", null))).isEqualTo(403);
        assertThat(status(send(fx.login(fx.user(true, true)), "GET", "/my-students", null))).isEqualTo(403);
    }
}

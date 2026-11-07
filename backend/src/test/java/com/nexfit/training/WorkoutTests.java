package com.nexfit.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
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

/** NEX-77, NEX-78, NEX-79 (US-53, US-54, US-55): entrenamientos, racha, meta semanal y peso corporal. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WorkoutTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static final AtomicInteger SEQ = new AtomicInteger();

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

    /** Una rutina con dos ejercicios; ids[0] y ids[1] son routine_exercises.id. */
    private record Plan(long routineId, long[] ids) { }

    private Person client(long orgId) {
        Account a = fx.user(false, true);
        return new Person(a, fx.member(a, orgId, "client", true));
    }

    private Person trainer(long orgId) {
        Account a = fx.user(false, true);
        long member = fx.member(a, orgId, "trainer", true);
        jdbc.update("INSERT INTO trainer_profiles (member_id, organization_id) VALUES (?, ?)", member, orgId);
        return new Person(a, member);
    }

    private Plan routine(long orgId, Person trainer, Person client, boolean active) {
        long routine = jdbc.queryForObject("""
                INSERT INTO routines (organization_id, trainer_member_id, client_member_id, name, is_active)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, trainer.memberId(), client.memberId(),
            "Rutina " + SEQ.incrementAndGet(), active);
        long[] ids = new long[2];
        for (int i = 0; i < 2; i++) {
            long exercise = jdbc.queryForObject("INSERT INTO exercises (name, is_active) VALUES (?, true) RETURNING id",
                Long.class, "Ejercicio de entreno " + SEQ.incrementAndGet());
            ids[i] = jdbc.queryForObject("""
                    INSERT INTO routine_exercises (routine_id, exercise_id, exec_order, series, repetitions, rest_seconds)
                    VALUES (?, ?, ?, 3, 10, 60) RETURNING id""", Long.class, routine, exercise, i + 1);
        }
        return new Plan(routine, ids);
    }

    private LocalDate today() {
        return jdbc.queryForObject("SELECT (now() AT TIME ZONE 'America/Bogota')::date", LocalDate.class);
    }

    private void logDirect(long orgId, Person client, Plan plan, LocalDate day) {
        jdbc.update("INSERT INTO workout_logs (organization_id, member_id, routine_id, performed_on) VALUES (?, ?, ?, ?)",
            orgId, client.memberId(), plan.routineId(), day);
    }

    private MvcResult send(Cookie session, String method, String uri, String json) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(uri);
            case "PUT" -> put(uri);
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

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static String workout(Plan plan, String extra, long... done) {
        StringBuilder ids = new StringBuilder();
        for (long id : done) {
            ids.append(ids.length() == 0 ? "" : ",").append(id);
        }
        return "{\"routineId\":" + plan.routineId() + (extra == null ? "" : "," + extra)
            + ",\"completedExerciseIds\":[" + ids + "]}";
    }

    private String progress(Cookie s) throws Exception {
        return body(send(s, "GET", "/my-progress", null));
    }

    // ---- US-53 · registrar ---------------------------------------------------------------

    @Test
    void elClienteRegistraUnEntrenamientoConLaFechaLocalYLosEjerciciosMarcados() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        Plan plan = routine(org.id(), t, c, true);
        Cookie s = fx.login(c.account());

        MvcResult r = send(s, "POST", "/workouts", workout(plan, null, plan.ids()[1]));

        assertThat(status(r)).as(body(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"performedOn\":\"" + today() + "\"").contains("\"routineId\":" + plan.routineId())
            .contains("\"routineExerciseId\":" + plan.ids()[1]).doesNotContain("\"routineExerciseId\":" + plan.ids()[0]);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_log_items", Integer.class)).isEqualTo(1);
        long id = jdbc.queryForObject("SELECT max(id) FROM workout_logs WHERE member_id = ?", Long.class, c.memberId());
        assertThat(body(send(s, "GET", "/workouts", null))).contains("\"completedCount\":1").contains("\"totalCount\":2");
        assertThat(status(send(s, "GET", "/workouts/" + id, null))).isEqualTo(200);
    }

    @Test
    void sePuedeRegistrarUnDiaPasadoPeroNoUnoFuturoNiDatosInvalidos() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        Plan plan = routine(org.id(), t, c, true);
        Cookie s = fx.login(c.account());
        long a = plan.ids()[0];

        MvcResult past = send(s, "POST", "/workouts", workout(plan, "\"performedOn\":\"" + today().minusDays(3) + "\"", a));
        MvcResult future = send(s, "POST", "/workouts", workout(plan, "\"performedOn\":\"" + today().plusDays(1) + "\"", a));
        MvcResult badDate = send(s, "POST", "/workouts", workout(plan, "\"performedOn\":\"ayer\"", a));
        MvcResult empty = send(s, "POST", "/workouts", workout(plan, null));
        MvcResult foreignItem = send(s, "POST", "/workouts", workout(plan, null, 999_999_999L));
        MvcResult noRoutine = send(s, "POST", "/workouts", "{\"completedExerciseIds\":[" + a + "]}");

        assertThat(status(past)).isEqualTo(201);
        assertThat(status(future)).isEqualTo(400);
        assertThat(body(future)).contains("performedOn");
        assertThat(status(badDate)).isEqualTo(400);
        assertThat(status(empty)).isEqualTo(400);
        assertThat(body(empty)).contains("completedExerciseIds");
        assertThat(status(foreignItem)).isEqualTo(400);
        assertThat(status(noRoutine)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_logs WHERE member_id = ?", Integer.class,
            c.memberId())).isEqualTo(1);
    }

    @Test
    void unEjercicioDeOtraRutinaNoCuentaYLosRepetidosSeUnifican() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        Plan mine = routine(org.id(), t, c, true);
        Plan other = routine(org.id(), t, c, true);
        Cookie s = fx.login(c.account());

        MvcResult mixed = send(s, "POST", "/workouts", workout(mine, null, mine.ids()[0], other.ids()[0]));
        MvcResult repeated = send(s, "POST", "/workouts", workout(mine, null, mine.ids()[0], mine.ids()[0]));

        assertThat(status(mixed)).isEqualTo(400);
        assertThat(status(repeated)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_log_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void rutinaArchivadaAjenaOInexistenteSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        Person c2 = client(org.id());
        Person foreignTrainer = trainer(other.id());
        Person foreignClient = client(other.id());
        Plan archived = routine(org.id(), t, c, false);
        Plan ofOtherClient = routine(org.id(), t, c2, true);
        Plan ofOtherGym = routine(other.id(), foreignTrainer, foreignClient, true);
        Cookie s = fx.login(c.account());

        MvcResult a = send(s, "POST", "/workouts", workout(archived, null, archived.ids()[0]));
        MvcResult b = send(s, "POST", "/workouts", workout(ofOtherClient, null, ofOtherClient.ids()[0]));
        MvcResult d = send(s, "POST", "/workouts", workout(ofOtherGym, null, ofOtherGym.ids()[0]));
        MvcResult e = send(s, "POST", "/workouts", "{\"routineId\":999999999,\"completedExerciseIds\":[1]}");

        assertThat(status(a)).isEqualTo(409);
        assertThat(body(a)).contains("ROUTINE_ARCHIVED");
        assertThat(status(b)).isEqualTo(404);
        assertThat(status(d)).isEqualTo(404);
        assertThat(status(e)).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_logs", Integer.class)).isZero();
    }

    @Test
    void cadaClienteVeSoloSusEntrenamientos() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c1 = client(org.id());
        Person c2 = client(org.id());
        Plan p1 = routine(org.id(), t, c1, true);
        Plan p2 = routine(org.id(), t, c2, true);
        send(fx.login(c1.account()), "POST", "/workouts", workout(p1, null, p1.ids()[0]));
        send(fx.login(c2.account()), "POST", "/workouts", workout(p2, null, p2.ids()[0]));
        long idOfC2 = jdbc.queryForObject("SELECT max(id) FROM workout_logs WHERE member_id = ?", Long.class,
            c2.memberId());

        String list = body(send(fx.login(c1.account()), "GET", "/workouts", null));

        assertThat(list).contains("\"routineId\":" + p1.routineId()).doesNotContain("\"routineId\":" + p2.routineId());
        assertThat(status(send(fx.login(c1.account()), "GET", "/workouts/" + idOfC2, null))).isEqualTo(404);
    }

    @Test
    void soloElClienteUsaEntrenamientosRachaMetaYPeso() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        Plan plan = routine(org.id(), t, c, true);
        long id = 1;
        List<Cookie> others = List.of(fx.login(t.account()), fx.login(fx.actor(org.id(), "staff")),
            fx.login(org.admin()), fx.login(fx.user(true, true)));

        for (Cookie o : others) {
            assertThat(status(send(o, "POST", "/workouts", workout(plan, null, plan.ids()[0])))).isEqualTo(403);
            assertThat(status(send(o, "GET", "/workouts", null))).isEqualTo(403);
            assertThat(status(send(o, "GET", "/workouts/" + id, null))).isEqualTo(403);
            assertThat(status(send(o, "GET", "/my-progress", null))).isEqualTo(403);
            assertThat(status(send(o, "PUT", "/my-goal", "{\"weeklyGoal\":3}"))).isEqualTo(403);
            assertThat(status(send(o, "POST", "/body-weight", "{\"weightKg\":70}"))).isEqualTo(403);
            assertThat(status(send(o, "GET", "/body-weight", null))).isEqualTo(403);
        }
    }

    // ---- US-54 · racha y meta ------------------------------------------------------------

    @Test
    void laRachaCuentaDiasSeguidosHastaHoyOAyer() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person upToToday = client(org.id());
        Person upToYesterday = client(org.id());
        Person stale = client(org.id());
        Person gap = client(org.id());
        Person none = client(org.id());
        LocalDate today = today();
        Plan p1 = routine(org.id(), t, upToToday, true);
        Plan p2 = routine(org.id(), t, upToYesterday, true);
        Plan p3 = routine(org.id(), t, stale, true);
        Plan p4 = routine(org.id(), t, gap, true);
        for (int i = 0; i < 3; i++) {
            logDirect(org.id(), upToToday, p1, today.minusDays(i));
        }
        logDirect(org.id(), upToToday, p1, today); // dos entrenamientos el mismo día cuentan un día
        logDirect(org.id(), upToYesterday, p2, today.minusDays(1));
        logDirect(org.id(), upToYesterday, p2, today.minusDays(2));
        logDirect(org.id(), stale, p3, today.minusDays(2));
        logDirect(org.id(), stale, p3, today.minusDays(3));
        logDirect(org.id(), gap, p4, today);
        logDirect(org.id(), gap, p4, today.minusDays(2));

        assertThat(progress(fx.login(upToToday.account()))).contains("\"streak\":3");
        assertThat(progress(fx.login(upToYesterday.account()))).contains("\"streak\":2");
        assertThat(progress(fx.login(stale.account()))).contains("\"streak\":0");
        assertThat(progress(fx.login(gap.account()))).contains("\"streak\":1");
        assertThat(progress(fx.login(none.account()))).contains("\"streak\":0").contains("\"weeklyGoal\":null")
            .contains("\"weekCount\":0");
    }

    @Test
    void elAvanceSemanalVaDeLunesADomingoYSeComparaConLaMeta() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        Plan plan = routine(org.id(), t, c, true);
        Cookie s = fx.login(c.account());
        LocalDate today = today();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        logDirect(org.id(), c, plan, today);
        logDirect(org.id(), c, plan, today.minusDays(9));
        logDirect(org.id(), c, plan, monday.minusDays(1)); // el domingo anterior no cuenta
        long expected = (today.minusDays(9).isBefore(monday) ? 0 : 1) + 1;

        MvcResult goal = send(s, "PUT", "/my-goal", "{\"weeklyGoal\":4}");

        assertThat(status(goal)).isEqualTo(200);
        assertThat(body(goal)).contains("\"weeklyGoal\":4").contains("\"weekCount\":" + expected)
            .contains("\"weekStart\":\"" + monday + "\"").contains("\"weekEnd\":\"" + monday.plusDays(6) + "\"")
            .contains("\"totalWorkouts\":3");
        assertThat(progress(s)).contains("\"weeklyGoal\":4");
    }

    @Test
    void laMetaDebeEstarEntre1y7YSePuedeQuitar() throws Exception {
        Org org = fx.orgWithAdmin();
        Person c = client(org.id());
        Cookie s = fx.login(c.account());

        for (String bad : new String[] {"0", "8", "-1", "2.5", "\"tres\""}) {
            MvcResult r = send(s, "PUT", "/my-goal", "{\"weeklyGoal\":" + bad + "}");
            assertThat(status(r)).as(bad).isEqualTo(400);
            assertThat(body(r)).contains("weeklyGoal");
        }
        assertThat(status(send(s, "PUT", "/my-goal", "{}"))).isEqualTo(400);
        assertThat(status(send(s, "PUT", "/my-goal", "{\"weeklyGoal\":1}"))).isEqualTo(200);
        assertThat(status(send(s, "PUT", "/my-goal", "{\"weeklyGoal\":7}"))).isEqualTo(200);
        assertThat(body(send(s, "PUT", "/my-goal", "{\"weeklyGoal\":null}"))).contains("\"weeklyGoal\":null");
        assertThat(jdbc.queryForObject("SELECT weekly_goal_sessions FROM organization_members WHERE id = ?",
            Integer.class, c.memberId())).isNull();
    }

    // ---- US-55 · peso corporal -----------------------------------------------------------

    @Test
    void elPesoSeRegistraYElSegundoDelMismoDiaLoReemplaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Person c = client(org.id());
        Cookie s = fx.login(c.account());

        MvcResult first = send(s, "POST", "/body-weight", "{\"weightKg\":70.5}");
        MvcResult second = send(s, "POST", "/body-weight", "{\"weightKg\":71}");

        assertThat(status(first)).isEqualTo(201);
        assertThat(body(first)).contains("\"weightKg\":70.5").contains("\"measuredOn\":\"" + today() + "\"");
        assertThat(status(second)).isEqualTo(200);
        assertThat(body(second)).contains("\"weightKg\":71");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM body_weight_records WHERE member_id = ?", Integer.class,
            c.memberId())).isEqualTo(1);
    }

    @Test
    void elPesoDebeEstarEntre20y500YNoTenerFechaFutura() throws Exception {
        Org org = fx.orgWithAdmin();
        Person c = client(org.id());
        Cookie s = fx.login(c.account());

        for (String bad : new String[] {"19.99", "500.01", "0", "-70", "70.123", "\"setenta\"", "null"}) {
            MvcResult r = send(s, "POST", "/body-weight", "{\"weightKg\":" + bad + "}");
            assertThat(status(r)).as(bad).isEqualTo(400);
            assertThat(body(r)).contains("weightKg");
        }
        MvcResult future = send(s, "POST", "/body-weight", "{\"weightKg\":70,\"measuredOn\":\"" + today().plusDays(1) + "\"}");
        assertThat(status(future)).isEqualTo(400);
        assertThat(body(future)).contains("measuredOn");
        assertThat(status(send(s, "POST", "/body-weight", "{\"weightKg\":20,\"measuredOn\":\"" + today().minusDays(1) + "\"}")))
            .isEqualTo(201);
        assertThat(status(send(s, "POST", "/body-weight", "{\"weightKg\":500,\"measuredOn\":\"" + today().minusDays(2) + "\"}")))
            .isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM body_weight_records WHERE member_id = ?", Integer.class,
            c.memberId())).isEqualTo(2);
    }

    @Test
    void elHistorialDePesoVaPorFechaYEsPrivado() throws Exception {
        Org org = fx.orgWithAdmin();
        Person c1 = client(org.id());
        Person c2 = client(org.id());
        Cookie s1 = fx.login(c1.account());
        send(s1, "POST", "/body-weight", "{\"weightKg\":72,\"measuredOn\":\"" + today().minusDays(10) + "\"}");
        send(s1, "POST", "/body-weight", "{\"weightKg\":71,\"measuredOn\":\"" + today().minusDays(5) + "\"}");
        send(s1, "POST", "/body-weight", "{\"weightKg\":70}");
        send(fx.login(c2.account()), "POST", "/body-weight", "{\"weightKg\":99}");

        String all = body(send(s1, "GET", "/body-weight", null));
        String ranged = body(send(s1, "GET", "/body-weight?from=" + today().minusDays(6), null));
        MvcResult badRange = send(s1, "GET", "/body-weight?from=ayer", null);

        assertThat(all.indexOf("\"weightKg\":72")).isLessThan(all.indexOf("\"weightKg\":71"));
        assertThat(all.indexOf("\"weightKg\":71")).isLessThan(all.indexOf("\"weightKg\":70"));
        assertThat(all).doesNotContain("99");
        assertThat(ranged).doesNotContain("\"weightKg\":72").contains("\"weightKg\":71");
        assertThat(status(badRange)).isEqualTo(400);
    }
}

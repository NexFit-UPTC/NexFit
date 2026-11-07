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

/** NEX-53, NEX-60, NEX-61, NEX-76 (US-42, US-43, US-44, US-52): rutinas del entrenador. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RoutineTests {

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

    private void assign(long orgId, Person trainer, Person client) {
        jdbc.update("INSERT INTO trainer_clients (trainer_member_id, client_member_id, organization_id, is_active) "
            + "VALUES (?, ?, ?, true)", trainer.memberId(), client.memberId(), orgId);
    }

    private long exercise(Long orgId, boolean active) {
        return jdbc.queryForObject("INSERT INTO exercises (organization_id, name, is_active) VALUES (?, ?, ?) "
            + "RETURNING id", Long.class, orgId, "Ejercicio de rutina " + SEQ.incrementAndGet(), active);
    }

    private MvcResult send(Cookie session, String method, String uri, String json) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(uri);
            case "PUT" -> put(uri);
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

    private static String item(long exercise, int order, int series, int reps, int rest) {
        return "{\"exerciseId\":%d,\"order\":%d,\"series\":%d,\"repetitions\":%d,\"restSeconds\":%d}"
            .formatted(exercise, order, series, reps, rest);
    }

    private static String routine(Long client, String name, String extra, String... items) {
        return "{" + (client == null ? "" : "\"clientMemberId\":" + client + ",") + "\"name\":\"" + name + "\","
            + (extra == null ? "" : extra + ",") + "\"exercises\":[" + String.join(",", items) + "]}";
    }

    private long createOk(Cookie trainer, long client, String name, String... items) throws Exception {
        MvcResult r = send(trainer, "POST", "/routines", routine(client, name, null, items));
        assertThat(status(r)).as(body(r)).isEqualTo(201);
        return jdbc.queryForObject("SELECT max(id) FROM routines WHERE name = ?", Long.class, name);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private int routinesIn(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM routines WHERE organization_id = ?", Integer.class, orgId);
    }

    // ---- US-42 · crear -------------------------------------------------------------------

    @Test
    void elEntrenadorCreaUnaRutinaParaSuAlumnoConSusEjerciciosEnOrden() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long press = exercise(null, true);
        long squat = exercise(org.id(), true);

        MvcResult r = send(fx.login(t.account()), "POST", "/routines", routine(c.memberId(), "Fuerza A",
            "\"category\":\"strength\",\"level\":\"beginner\",\"estimatedMinutes\":45,\"estimatedKcal\":300",
            item(squat, 2, 4, 8, 90), item(press, 1, 3, 12, 60)));

        assertThat(status(r)).isEqualTo(201);
        String json = body(r);
        assertThat(json).contains("\"name\":\"Fuerza A\"").contains("\"category\":\"strength\"")
            .contains("\"level\":\"beginner\"").contains("\"estimatedMinutes\":45").contains("\"estimatedKcal\":300")
            .contains("\"active\":true").contains("\"trainerMemberId\":" + t.memberId())
            .contains("\"clientMemberId\":" + c.memberId());
        // Se devuelven por orden de ejecución, no por el orden en que se enviaron.
        assertThat(json.indexOf("\"exerciseId\":" + press)).isLessThan(json.indexOf("\"exerciseId\":" + squat));
        assertThat(json).contains("\"series\":3").contains("\"repetitions\":12").contains("\"restSeconds\":60");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_exercises e JOIN routines r ON r.id = e.routine_id "
            + "WHERE r.organization_id = ?", Integer.class, org.id())).isEqualTo(2);
    }

    @Test
    void sinOrdenSeUsaLaPosicionYCategoriaNivelYEstimadosSonOpcionales() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long a = exercise(null, true);
        long b = exercise(null, true);
        String sinOrden = "{\"exerciseId\":%d,\"series\":3,\"repetitions\":10,\"restSeconds\":0}";

        MvcResult r = send(fx.login(t.account()), "POST", "/routines", routine(c.memberId(), "Libre", null,
            sinOrden.formatted(a), sinOrden.formatted(b)));

        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"order\":1").contains("\"order\":2").contains("\"category\":null")
            .contains("\"estimatedMinutes\":null");
    }

    @Test
    void rechazaDatosInvalidosYNoCreaNada() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long e = exercise(null, true);
        Cookie s = fx.login(t.account());
        String ok = item(e, 1, 3, 10, 60);
        long cm = c.memberId();

        assertThat(body(send(s, "POST", "/routines", routine(cm, " ", null, ok)))).contains("\"field\":\"name\"");
        assertThat(body(send(s, "POST", "/routines", routine(cm, "x".repeat(151), null, ok))))
            .contains("\"field\":\"name\"");
        assertThat(body(send(s, "POST", "/routines", routine(cm, "R", "\"category\":\"yoga\"", ok))))
            .contains("\"field\":\"category\"");
        assertThat(body(send(s, "POST", "/routines", routine(cm, "R", "\"level\":\"pro\"", ok))))
            .contains("\"field\":\"level\"");
        assertThat(body(send(s, "POST", "/routines", routine(cm, "R", "\"estimatedMinutes\":0", ok))))
            .contains("\"field\":\"estimatedMinutes\"");
        assertThat(body(send(s, "POST", "/routines", routine(cm, "R", "\"estimatedKcal\":-5", ok))))
            .contains("\"field\":\"estimatedKcal\"");
        assertThat(body(send(s, "POST", "/routines", routine(cm, "R", null)))).contains("\"field\":\"exercises\"");
        assertThat(body(send(s, "POST", "/routines", routine(null, "R", null, ok))))
            .contains("\"field\":\"clientMemberId\"");
        assertThat(body(send(s, "POST", "/routines", routine(cm, "R", null, item(e, 1, 0, 10, 60)))))
            .contains("\"field\":\"exercises[0].series\"");
        assertThat(body(send(s, "POST", "/routines", routine(cm, "R", null, item(e, 1, 3, 0, 60)))))
            .contains("\"field\":\"exercises[0].repetitions\"");
        assertThat(body(send(s, "POST", "/routines", routine(cm, "R", null, item(e, 1, 3, 10, -1)))))
            .contains("\"field\":\"exercises[0].restSeconds\"");
        MvcResult dup = send(s, "POST", "/routines", routine(cm, "R", null, item(e, 1, 3, 10, 60),
            item(e, 1, 3, 10, 60)));
        assertThat(status(dup)).isEqualTo(400);
        assertThat(body(dup)).contains("VALIDATION_ERROR").contains("exercises[1].order");
        assertThat(routinesIn(org.id())).isZero();
    }

    @Test
    void soloSePuedeCrearParaUnAlumnoActivoDelPropioEntrenador() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person otherTrainer = trainer(org.id());
        Person mine = client(org.id());
        Person notMine = client(org.id());
        Person retired = client(org.id());
        Person foreign = client(other.id());
        assign(org.id(), t, mine);
        assign(org.id(), otherTrainer, notMine);
        assign(org.id(), t, retired);
        jdbc.update("UPDATE trainer_clients SET is_active = false WHERE client_member_id = ?", retired.memberId());
        long e = exercise(null, true);
        Cookie s = fx.login(t.account());

        for (Person p : new Person[] {notMine, retired, foreign}) {
            MvcResult r = send(s, "POST", "/routines", routine(p.memberId(), "R", null, item(e, 1, 3, 10, 60)));
            assertThat(status(r)).isEqualTo(400);
            assertThat(body(r)).contains("clientMemberId");
        }
        assertThat(status(send(s, "POST", "/routines", routine(mine.memberId(), "R", null, item(e, 1, 3, 10, 60)))))
            .isEqualTo(201);
        assertThat(routinesIn(org.id())).isEqualTo(1);
        assertThat(routinesIn(other.id())).isZero();
    }

    @Test
    void unEjercicioInactivoAjenoOInexistenteSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long inactive = exercise(null, false);
        long foreignOwn = exercise(other.id(), true);
        Cookie s = fx.login(t.account());

        for (long bad : new long[] {inactive, foreignOwn, 999_999_999L}) {
            MvcResult r = send(s, "POST", "/routines", routine(c.memberId(), "R", null, item(bad, 1, 3, 10, 60)));
            assertThat(status(r)).isEqualTo(400);
            assertThat(body(r)).contains("exercises[0].exerciseId");
        }
        assertThat(routinesIn(org.id())).isZero();
    }

    @Test
    void soloElEntrenadorCrea() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long e = exercise(null, true);
        String json = routine(c.memberId(), "R", null, item(e, 1, 3, 10, 60));

        assertThat(status(send(fx.login(c.account()), "POST", "/routines", json))).isEqualTo(403);
        assertThat(status(send(fx.login(fx.actor(org.id(), "staff")), "POST", "/routines", json))).isEqualTo(403);
        assertThat(status(send(fx.login(org.admin()), "POST", "/routines", json))).isEqualTo(403);
        assertThat(routinesIn(org.id())).isZero();
    }

    // ---- US-52 · consultar ---------------------------------------------------------------

    @Test
    void cadaQuienVeSoloSusRutinasYElClienteTambienLasArchivadas() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t1 = trainer(org.id());
        Person t2 = trainer(org.id());
        Person c1 = client(org.id());
        Person c2 = client(org.id());
        assign(org.id(), t1, c1);
        assign(org.id(), t2, c2);
        long e = exercise(null, true);
        Cookie s1 = fx.login(t1.account());
        long mine = createOk(s1, c1.memberId(), "Rutina de c1", item(e, 1, 3, 10, 60));
        long old = createOk(s1, c1.memberId(), "Rutina vieja de c1", item(e, 1, 3, 10, 60));
        long other = createOk(fx.login(t2.account()), c2.memberId(), "Rutina de c2", item(e, 1, 3, 10, 60));
        send(s1, "PATCH", "/routines/" + old + "/archive", null);

        String trainerAll = body(send(s1, "GET", "/routines", null));
        String trainerActive = body(send(s1, "GET", "/routines?active=true", null));
        String clientAll = body(send(fx.login(c1.account()), "GET", "/routines", null));

        assertThat(trainerAll).contains("Rutina de c1").contains("Rutina vieja de c1").doesNotContain("Rutina de c2");
        assertThat(trainerActive).contains("Rutina de c1").doesNotContain("Rutina vieja de c1");
        assertThat(clientAll).contains("Rutina de c1").contains("Rutina vieja de c1").doesNotContain("Rutina de c2")
            .contains("\"exerciseCount\":1");
        assertThat(status(send(s1, "GET", "/routines/" + mine, null))).isEqualTo(200);
        assertThat(status(send(s1, "GET", "/routines/" + other, null))).isEqualTo(404);
        assertThat(status(send(fx.login(c1.account()), "GET", "/routines/" + mine, null))).isEqualTo(200);
        assertThat(status(send(fx.login(c1.account()), "GET", "/routines/" + other, null))).isEqualTo(404);
        assertThat(status(send(fx.login(c2.account()), "GET", "/routines/" + mine, null))).isEqualTo(404);
    }

    @Test
    void filtraPorAlumnoYCategoria() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c1 = client(org.id());
        Person c2 = client(org.id());
        assign(org.id(), t, c1);
        assign(org.id(), t, c2);
        long e = exercise(null, true);
        Cookie s = fx.login(t.account());
        send(s, "POST", "/routines", routine(c1.memberId(), "Fuerza uno", "\"category\":\"strength\"",
            item(e, 1, 3, 10, 60)));
        send(s, "POST", "/routines", routine(c2.memberId(), "Cardio dos", "\"category\":\"cardio\"",
            item(e, 1, 3, 10, 60)));

        String byClient = body(send(s, "GET", "/routines?clientMemberId=" + c2.memberId(), null));
        String byCategory = body(send(s, "GET", "/routines?category=strength", null));
        MvcResult bad = send(s, "GET", "/routines?category=yoga", null);

        assertThat(byClient).contains("Cardio dos").doesNotContain("Fuerza uno");
        assertThat(byCategory).contains("Fuerza uno").doesNotContain("Cardio dos");
        assertThat(status(bad)).isEqualTo(400);
    }

    @Test
    void staffAdminYMasterNoVenRutinasYOtroGimnasioNoExiste() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long e = exercise(null, true);
        long id = createOk(fx.login(t.account()), c.memberId(), "Privada", item(e, 1, 3, 10, 60));

        assertThat(status(send(fx.login(fx.actor(org.id(), "staff")), "GET", "/routines", null))).isEqualTo(403);
        assertThat(status(send(fx.login(org.admin()), "GET", "/routines/" + id, null))).isEqualTo(403);
        assertThat(status(send(fx.login(fx.user(true, true)), "GET", "/routines", null))).isEqualTo(403);
        Person foreignTrainer = trainer(other.id());
        assertThat(status(send(fx.login(foreignTrainer.account()), "GET", "/routines/" + id, null))).isEqualTo(404);
    }

    // ---- US-43 · modificar ---------------------------------------------------------------

    @Test
    void modificarReemplazaDatosYEjercicios() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long a = exercise(null, true);
        long b = exercise(null, true);
        long d = exercise(org.id(), true);
        Cookie s = fx.login(t.account());
        long id = createOk(s, c.memberId(), "Original", item(a, 1, 3, 10, 60), item(b, 2, 3, 10, 60));

        MvcResult r = send(s, "PUT", "/routines/" + id, routine(null, "Nueva", "\"category\":\"cardio\"",
            item(d, 1, 5, 5, 30), item(a, 2, 2, 20, 0)));

        assertThat(status(r)).as(body(r)).isEqualTo(200);
        String json = body(r);
        assertThat(json).contains("\"name\":\"Nueva\"").contains("\"category\":\"cardio\"")
            .contains("\"clientMemberId\":" + c.memberId()).doesNotContain("\"exerciseId\":" + b);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_exercises WHERE routine_id = ?", Integer.class,
            id)).isEqualTo(2);
        assertThat(json.indexOf("\"exerciseId\":" + d)).isLessThan(json.indexOf("\"exerciseId\":" + a));
    }

    @Test
    void modificarConservaUnEjercicioQueSeDesactivoDespues() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long a = exercise(null, true);
        Cookie s = fx.login(t.account());
        long id = createOk(s, c.memberId(), "Conserva", item(a, 1, 3, 10, 60));
        jdbc.update("UPDATE exercises SET is_active = false WHERE id = ?", a);

        MvcResult keep = send(s, "PUT", "/routines/" + id, routine(null, "Conserva", null, item(a, 1, 4, 10, 60)));
        long inactiveNew = exercise(null, false);
        MvcResult add = send(s, "PUT", "/routines/" + id, routine(null, "Conserva", null, item(a, 1, 4, 10, 60),
            item(inactiveNew, 2, 3, 10, 60)));

        assertThat(status(keep)).isEqualTo(200);
        assertThat(status(add)).isEqualTo(400);
        assertThat(body(add)).contains("exercises[1].exerciseId");
    }

    @Test
    void unaRutinaInvalidaOAjenaNoSeModificaYLaArchivadaDa409() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person other = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long a = exercise(null, true);
        Cookie s = fx.login(t.account());
        long id = createOk(s, c.memberId(), "Estable", item(a, 1, 3, 10, 60));

        MvcResult invalid = send(s, "PUT", "/routines/" + id, routine(null, "", null, item(a, 1, 3, 10, 60)));
        MvcResult foreign = send(fx.login(other.account()), "PUT", "/routines/" + id,
            routine(null, "Robada", null, item(a, 1, 3, 10, 60)));
        MvcResult asClient = send(fx.login(c.account()), "PUT", "/routines/" + id,
            routine(null, "Del cliente", null, item(a, 1, 3, 10, 60)));
        MvcResult missing = send(s, "PUT", "/routines/999999999", routine(null, "X", null, item(a, 1, 3, 10, 60)));

        assertThat(status(invalid)).isEqualTo(400);
        assertThat(status(foreign)).isEqualTo(404);
        assertThat(status(asClient)).isEqualTo(403);
        assertThat(status(missing)).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT name FROM routines WHERE id = ?", String.class, id))
            .isEqualTo("Estable");

        send(s, "PATCH", "/routines/" + id + "/archive", null);
        MvcResult archived = send(s, "PUT", "/routines/" + id, routine(null, "Tarde", null, item(a, 1, 3, 10, 60)));
        assertThat(status(archived)).isEqualTo(409);
        assertThat(body(archived)).contains("ROUTINE_ARCHIVED");
    }

    @Test
    void siElAlumnoYaNoEsDelEntrenadorLaRutinaNoSeModificaPeroSiSeArchiva() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long a = exercise(null, true);
        Cookie s = fx.login(t.account());
        long id = createOk(s, c.memberId(), "Se queda", item(a, 1, 3, 10, 60));
        jdbc.update("UPDATE trainer_clients SET is_active = false WHERE client_member_id = ?", c.memberId());

        MvcResult edit = send(s, "PUT", "/routines/" + id, routine(null, "Cambio", null, item(a, 1, 3, 10, 60)));
        MvcResult archive = send(s, "PATCH", "/routines/" + id + "/archive", null);
        String clientView = body(send(fx.login(c.account()), "GET", "/routines", null));

        assertThat(status(edit)).isEqualTo(409);
        assertThat(body(edit)).contains("NOT_ASSIGNED");
        assertThat(status(archive)).isEqualTo(200);
        assertThat(clientView).contains("Se queda");
    }

    // ---- US-44 · archivar ----------------------------------------------------------------

    @Test
    void archivarEsIdempotenteYSoloDelDueno() throws Exception {
        Org org = fx.orgWithAdmin();
        Person t = trainer(org.id());
        Person other = trainer(org.id());
        Person c = client(org.id());
        assign(org.id(), t, c);
        long a = exercise(null, true);
        Cookie s = fx.login(t.account());
        long id = createOk(s, c.memberId(), "A archivar", item(a, 1, 3, 10, 60));

        MvcResult foreign = send(fx.login(other.account()), "PATCH", "/routines/" + id + "/archive", null);
        MvcResult first = send(s, "PATCH", "/routines/" + id + "/archive", null);
        MvcResult second = send(s, "PATCH", "/routines/" + id + "/archive", null);
        MvcResult asClient = send(fx.login(c.account()), "PATCH", "/routines/" + id + "/archive", null);

        assertThat(status(foreign)).isEqualTo(404);
        assertThat(status(first)).isEqualTo(200);
        assertThat(body(first)).contains("\"active\":false");
        assertThat(status(second)).isEqualTo(200);
        assertThat(status(asClient)).isEqualTo(403);
        assertThat(status(send(s, "PATCH", "/routines/999999999/archive", null))).isEqualTo(404);
        // Archivar no borra: la rutina y sus ejercicios siguen en la base.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_exercises WHERE routine_id = ?", Integer.class,
            id)).isEqualTo(1);
    }
}

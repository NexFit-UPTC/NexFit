package com.nexfit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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

/** NEX-40 (US-29): programar y gestionar clases. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ClassTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    /** Día de las pruebas, lejos en el futuro: las reservas exigen una clase que aún no empieza. */
    private static final String DAY = "2031-03-10";

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

    /** Hora local de Bogotá (-05:00) del día de pruebas, p. ej. at(18) → 2031-03-10T18:00:00-05:00. */
    private static String at(int hour) {
        return "%sT%02d:00:00-05:00".formatted(DAY, hour);
    }

    private static String json(String name, long branchId, Long trainerId, String startsAt, int duration,
                               int capacity) {
        return "{\"name\":\"%s\",\"branchId\":%d,%s\"startsAt\":\"%s\",\"durationMinutes\":%d,\"capacity\":%d}"
            .formatted(name, branchId, trainerId == null ? "" : "\"trainerMemberId\":" + trainerId + ",",
                startsAt, duration, capacity);
    }

    private MvcResult create(Cookie session, String json) throws Exception {
        return mvc.perform(post("/classes").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult modify(Cookie session, long id, String json) throws Exception {
        return mvc.perform(put("/classes/" + id).with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult list(Cookie session, String query) throws Exception {
        return mvc.perform(get("/classes" + query).cookie(session)).andReturn();
    }

    private MvcResult one(Cookie session, long id) throws Exception {
        return mvc.perform(get("/classes/" + id).cookie(session)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static long idOf(String json) {
        return Long.parseLong(json.replaceAll("(?s).*?\"id\":(\\d+).*", "$1"));
    }

    private long branch(long orgId, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO branches (organization_id, name, address_line1, city, department, country, is_active)
            VALUES (?, 'Sede ' || gen_random_uuid(), 'Calle 1', 'Tunja', 'Boyacá', 'Colombia', ?) RETURNING id
            """, Long.class, orgId, active);
    }

    /** Entrenador activo con su perfil (classes.trainer_member_id apunta a trainer_profiles). */
    private long trainer(long orgId) {
        long memberId = fx.member(fx.user(false, true), orgId, "trainer", true);
        jdbc.update("INSERT INTO trainer_profiles (member_id, organization_id) VALUES (?, ?)", memberId, orgId);
        return memberId;
    }

    /** Clase programada directamente en la base (sin pasar por la API). */
    private long classRow(long orgId, long branchId, Long trainerId, String startsAt, int duration, int capacity) {
        return jdbc.queryForObject("""
            INSERT INTO classes (organization_id, branch_id, trainer_member_id, name, starts_at,
                                 duration_minutes, capacity, status)
            VALUES (?, ?, ?, 'Clase base', CAST(? AS timestamptz), ?, ?, 'scheduled') RETURNING id
            """, Long.class, orgId, branchId, trainerId, startsAt, duration, capacity);
    }

    private void cancel(long classId) {
        jdbc.update("UPDATE classes SET status = 'cancelled' WHERE id = ?", classId);
    }

    /** Cliente con membresía vigente que reserva la clase. */
    private void book(Org org, long classId) {
        long planId = jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, 'Plan ' || gen_random_uuid(), 50000, 365, true) RETURNING id
            """, Long.class, org.id());
        Account client = fx.user(false, true);
        long memberId = fx.member(client, org.id(), "client", true);
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id,
                                     start_date, end_date, status)
            VALUES (?, ?, ?, ?, DATE '2031-01-01', DATE '2031-12-31', 'active')
            """, org.id(), memberId, planId, org.adminMemberId());
        jdbc.update("INSERT INTO class_bookings (organization_id, class_id, member_id, status)"
            + " VALUES (?, ?, ?, 'confirmed')", org.id(), classId, memberId);
    }

    private int classCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM classes WHERE organization_id = ?", Integer.class, orgId);
    }

    private Map<String, Object> row(long classId) {
        return jdbc.queryForMap("""
            SELECT organization_id, branch_id, trainer_member_id, name, description, duration_minutes, capacity,
                   CAST(status AS text) AS status, starts_at FROM classes WHERE id = ?""", classId);
    }

    /** ¿La clase empieza exactamente en ese instante? (se compara en la base, sin depender del huso). */
    private boolean startsAtIs(long classId, String instant) {
        return jdbc.queryForObject("SELECT starts_at = CAST(? AS timestamptz) FROM classes WHERE id = ?",
            Boolean.class, instant, classId);
    }

    private void assertRejected(MvcResult r, String field) throws Exception {
        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r)).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"" + field + "\"");
    }

    private void assertConflict(MvcResult r, String code) throws Exception {
        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("\"code\":\"" + code + "\"");
    }

    // ---- criterio 1: crear una clase válida ----------------------------------------------

    // Con nombre, sede activa del gimnasio, fecha y hora, duración y capacidad > 0, la clase queda programada.
    @Test
    void elAdministradorProgramaUnaClaseEnUnaSedeActivaDeSuGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long trainer = trainer(org.id());

        MvcResult r = create(admin, "{\"name\":\"  Spinning \",\"description\":\"Cardio\",\"branchId\":" + branch
            + ",\"trainerMemberId\":" + trainer + ",\"startsAt\":\"" + at(18)
            + "\",\"durationMinutes\":60,\"capacity\":12}");

        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"name\":\"Spinning\"", "\"status\":\"scheduled\"", "\"capacity\":12",
            "\"confirmedBookings\":0", "\"freeSlots\":12", "\"trainerName\":\"Ana Pérez\"");
        Map<String, Object> row = row(idOf(body(r)));
        assertThat(row).containsEntry("organization_id", org.id()).containsEntry("branch_id", branch)
            .containsEntry("trainer_member_id", trainer).containsEntry("name", "Spinning")
            .containsEntry("description", "Cardio").containsEntry("duration_minutes", 60)
            .containsEntry("capacity", 12).containsEntry("status", "scheduled");
        assertThat(body(list(admin, ""))).contains("Spinning");
    }

    @Test
    void laClaseSePuedeProgramarSinEntrenador() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());

        MvcResult r = create(admin, json("Yoga", branch(org.id(), true), null, at(7), 45, 8));

        assertThat(status(r)).isEqualTo(201);
        assertThat(row(idOf(body(r))).get("trainer_member_id")).isNull();
    }

    // ---- criterio 2: capacidad 0, sede de otro gimnasio o inactiva -------------------------

    @Test
    void unaCapacidadCeroSeRechazaSinCrearNada() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);

        assertRejected(create(admin, json("Zumba", branch, null, at(18), 60, 0)), "capacity");
        assertRejected(create(admin, json("Zumba", branch, null, at(18), 60, -3)), "capacity");
        assertThat(classCount(org.id())).isZero();
    }

    @Test
    void unaSedeDeOtroGimnasioOInactivaSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());

        assertRejected(create(admin, json("Zumba", branch(other.id(), true), null, at(18), 60, 10)), "branchId");
        assertRejected(create(admin, json("Zumba", branch(org.id(), false), null, at(18), 60, 10)), "branchId");
        assertRejected(create(admin, json("Zumba", 987_654_321L, null, at(18), 60, 10)), "branchId");
        assertThat(classCount(org.id())).isZero();
        assertThat(classCount(other.id())).isZero();
    }

    @Test
    void losDatosObligatoriosSeValidan() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);

        assertRejected(create(admin, json("   ", branch, null, at(18), 60, 10)), "name");
        assertRejected(create(admin, json("Box", branch, null, at(18), 0, 10)), "durationMinutes");
        assertRejected(create(admin, json("Box", branch, null, "2031-03-10 18:00", 60, 10)), "startsAt");
        assertRejected(create(admin, "{\"name\":\"Box\",\"durationMinutes\":60,\"capacity\":10,\"startsAt\":\""
            + at(18) + "\"}"), "branchId");
        assertRejected(create(admin, "{\"name\":\"Box\",\"branchId\":" + branch
            + ",\"durationMinutes\":60,\"capacity\":10}"), "startsAt");
        assertThat(classCount(org.id())).isZero();
    }

    @Test
    void elGimnasioSiempreSaleDeLaSesionYNoDelCuerpo() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);

        MvcResult r = create(admin, "{\"name\":\"Box\",\"branchId\":" + branch + ",\"organizationId\":" + other.id()
            + ",\"startsAt\":\"" + at(18) + "\",\"durationMinutes\":60,\"capacity\":10}");

        assertThat(status(r)).isEqualTo(201);
        assertThat(row(idOf(body(r)))).containsEntry("organization_id", org.id());
        assertThat(classCount(other.id())).isZero();
    }

    // ---- criterio 3: entrenador y solapes (RN-36) ----------------------------------------

    // Un entrenador del gimnasio sin otra clase solapada se acepta; con una solapada o de otro gimnasio, se rechaza.
    @Test
    @ReglaDeNegocio("RN-36")
    void alEntrenadorSeLeAsignaUnaClaseSoloSiNoSeSolapaConOtra() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long trainer = trainer(org.id());
        classRow(org.id(), branch, trainer, at(18), 60, 10); // 18:00-19:00

        // Cruzada por el inicio, por el final y contenida: todas rechazadas.
        assertConflict(create(admin, json("A", branch, trainer, at(18), 30, 5)), "TRAINER_OVERLAP");
        assertConflict(create(admin, json("B", branch, trainer, "%sT17:30:00-05:00".formatted(DAY), 60, 5)),
            "TRAINER_OVERLAP");
        assertConflict(create(admin, json("C", branch, trainer, "%sT18:30:00-05:00".formatted(DAY), 60, 5)),
            "TRAINER_OVERLAP");
        assertThat(classCount(org.id())).isEqualTo(1);

        // Pegada antes o después (19:00 y 17:00-18:00) y en otro día: aceptadas.
        assertThat(status(create(admin, json("D", branch, trainer, at(19), 60, 5)))).isEqualTo(201);
        assertThat(status(create(admin, json("E", branch, trainer, at(17), 60, 5)))).isEqualTo(201);
        assertThat(status(create(admin, json("F", branch, trainer, "2031-03-11T18:00:00-05:00", 60, 5))))
            .isEqualTo(201);
    }

    @Test
    @ReglaDeNegocio("RN-36")
    void unaClaseCanceladaNoOcupaElHorarioDelEntrenador() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long trainer = trainer(org.id());
        cancel(classRow(org.id(), branch, trainer, at(18), 60, 10));

        assertThat(status(create(admin, json("Nueva", branch, trainer, at(18), 60, 10)))).isEqualTo(201);
    }

    @Test
    @ReglaDeNegocio("RN-36")
    void unEntrenadorDeOtroGimnasioOSinElRolOInactivoSeRechaza() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long foreignTrainer = trainer(other.id());
        long staff = fx.member(fx.user(false, true), org.id(), "staff", true);
        long inactiveTrainer = fx.member(fx.user(false, true), org.id(), "trainer", false);

        assertRejected(create(admin, json("X", branch, foreignTrainer, at(18), 60, 10)), "trainerMemberId");
        assertRejected(create(admin, json("X", branch, staff, at(18), 60, 10)), "trainerMemberId");
        assertRejected(create(admin, json("X", branch, inactiveTrainer, at(18), 60, 10)), "trainerMemberId");
        assertRejected(create(admin, json("X", branch, 987_654_321L, at(18), 60, 10)), "trainerMemberId");
        assertThat(classCount(org.id())).isZero();
    }

    // ---- criterio 4: capacidad frente a reservas (RN-36) -------------------------------------

    @Test
    @ReglaDeNegocio("RN-36")
    void noSePuedeReducirLaCapacidadPorDebajoDeLasReservasConfirmadas() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long id = classRow(org.id(), branch, null, at(18), 60, 5);
        book(org, id);
        book(org, id);
        book(org, id);

        assertConflict(modify(admin, id, json("Clase base", branch, null, at(18), 60, 2)),
            "CAPACITY_BELOW_BOOKINGS");
        assertThat(row(id)).containsEntry("capacity", 5);

        // Igual a las reservas confirmadas sí se permite.
        assertThat(status(modify(admin, id, json("Clase base", branch, null, at(18), 60, 3)))).isEqualTo(200);
        assertThat(row(id)).containsEntry("capacity", 3);
    }

    @Test
    void unaReservaCanceladaNoCuentaParaLaCapacidad() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long id = classRow(org.id(), branch, null, at(18), 60, 5);
        book(org, id);
        book(org, id);
        jdbc.update("UPDATE class_bookings SET status = 'cancelled', cancelled_at = now() WHERE id = "
            + "(SELECT min(id) FROM class_bookings WHERE class_id = ?)", id);

        assertThat(status(modify(admin, id, json("Clase base", branch, null, at(18), 60, 1)))).isEqualTo(200);
    }

    // ---- criterio 5: el listado muestra reservas y cupos ---------------------------------

    @Test
    void elListadoMuestraPorClaseLasReservasConfirmadasYLosCuposLibres() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long id = classRow(org.id(), branch, null, at(18), 60, 5);
        book(org, id);
        book(org, id);

        MvcResult r = list(admin, "");

        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r)).contains("\"id\":" + id, "\"confirmedBookings\":2", "\"freeSlots\":3",
            "\"totalElements\":1");
        assertThat(body(one(admin, id))).contains("\"confirmedBookings\":2", "\"freeSlots\":3");
    }

    @Test
    void elListadoSePaginaYSeFiltraPorSedeYFechas() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long north = branch(org.id(), true);
        long south = branch(org.id(), true);
        classRow(org.id(), north, null, at(7), 60, 5);
        classRow(org.id(), north, null, at(9), 60, 5);
        classRow(org.id(), south, null, at(11), 60, 5);

        assertThat(body(list(admin, "?size=2&page=0"))).contains("\"totalElements\":3", "\"totalPages\":2");
        assertThat(body(list(admin, "?branchId=" + south))).contains("\"totalElements\":1");
        // `from` incluye y `to` excluye: [08:00, 11:00) deja solo la de las 9.
        String window = "?from=" + at(8) + "&to=" + at(11);
        assertThat(body(list(admin, window))).contains("\"totalElements\":1");
        assertThat(status(list(admin, "?from=ayer"))).isEqualTo(400);
        assertThat(status(list(admin, "?status=otro"))).isEqualTo(400);
    }

    @Test
    void elListadoNuncaMuestraClasesDeOtroGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long foreign = classRow(other.id(), branch(other.id(), true), null, at(18), 60, 5);

        assertThat(body(list(admin, ""))).contains("\"totalElements\":0");
        assertThat(status(one(admin, foreign))).isEqualTo(404);
        assertThat(status(modify(admin, foreign, json("Hack", branch(org.id(), true), null, at(18), 60, 5))))
            .isEqualTo(404);
        assertThat(row(foreign)).containsEntry("name", "Clase base");
    }

    // ---- criterio 6: cambio de fecha u hora (D-21) -------------------------------------------

    @Test
    void conReservasConfirmadasNoSeCambiaLaFechaNiLaHora() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long id = classRow(org.id(), branch, null, at(18), 60, 5);
        book(org, id);

        assertConflict(modify(admin, id, json("Clase base", branch, null, at(19), 60, 5)), "CLASS_HAS_BOOKINGS");
        assertConflict(modify(admin, id, json("Clase base", branch, null, "2031-03-11T18:00:00-05:00", 60, 5)),
            "CLASS_HAS_BOOKINGS");
        assertThat(startsAtIs(id, at(18))).isTrue();

        // Lo demás sí se puede editar con reservas, incluso con la misma hora escrita en otro huso.
        assertThat(status(modify(admin, id, "{\"name\":\"Renombrada\",\"branchId\":" + branch
            + ",\"startsAt\":\"2031-03-10T23:00:00Z\",\"durationMinutes\":60,\"capacity\":8}"))).isEqualTo(200);
        assertThat(row(id)).containsEntry("name", "Renombrada").containsEntry("capacity", 8);
    }

    @Test
    void sinReservasElHorarioCambiaYSeRevalidaElSolapeDelEntrenador() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long trainer = trainer(org.id());
        long first = classRow(org.id(), branch, trainer, at(18), 60, 5);
        classRow(org.id(), branch, trainer, at(20), 60, 5);

        // Moverla sobre la de las 20:00 se rechaza; a las 21:00 se acepta.
        assertConflict(modify(admin, first, json("Clase base", branch, trainer, at(20), 60, 5)), "TRAINER_OVERLAP");
        assertThat(status(modify(admin, first, json("Clase base", branch, trainer, at(21), 60, 5)))).isEqualTo(200);
        // Una clase no choca consigo misma: puede alargarse sin cambiar el inicio mientras no toque otra.
        assertThat(status(modify(admin, first, json("Clase base", branch, trainer, at(21), 90, 5)))).isEqualTo(200);
    }

    @Test
    void unaClaseCanceladaNoAdmiteCambios() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long id = classRow(org.id(), branch, null, at(18), 60, 5);
        cancel(id);

        assertConflict(modify(admin, id, json("Otra", branch, null, at(18), 60, 5)), "CLASS_CANCELLED");
        assertThat(row(id)).containsEntry("name", "Clase base");
    }

    @Test
    void alModificarLaSedeNuevaDebeEstarActivaYSerDelGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        long id = classRow(org.id(), branch, null, at(18), 60, 5);

        assertRejected(modify(admin, id, json("Clase base", branch(org.id(), false), null, at(18), 60, 5)), "branchId");
        assertRejected(modify(admin, id, json("Clase base", branch(other.id(), true), null, at(18), 60, 5)),
            "branchId");
        assertThat(row(id)).containsEntry("branch_id", branch);
    }

    // ---- permisos y visibilidad ------------------------------------------------------------

    @Test
    void soloElAdministradorProgramaYModificaClases() throws Exception {
        Org org = fx.orgWithAdmin();
        long branch = branch(org.id(), true);
        long id = classRow(org.id(), branch, null, at(18), 60, 5);

        for (String role : new String[] {"staff", "trainer", "client"}) {
            Cookie session = fx.login(fx.actor(org.id(), role));
            assertThat(status(create(session, json("Box", branch, null, at(9), 60, 5)))).as(role).isEqualTo(403);
            assertThat(status(modify(session, id, json("Box", branch, null, at(9), 60, 5)))).as(role).isEqualTo(403);
        }
        assertThat(classCount(org.id())).isEqualTo(1);
        assertThat(row(id)).containsEntry("name", "Clase base");
    }

    @Test
    void staffEntrenadorYClienteSoloVenLasClasesProgramadas() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long branch = branch(org.id(), true);
        classRow(org.id(), branch, null, at(18), 60, 5);
        long cancelled = classRow(org.id(), branch, null, at(19), 60, 5);
        cancel(cancelled);

        for (String role : new String[] {"staff", "trainer", "client"}) {
            Cookie session = fx.login(fx.actor(org.id(), role));
            assertThat(body(list(session, ""))).as(role).contains("\"totalElements\":1");
            assertThat(body(list(session, "?status=cancelled"))).as(role).contains("\"totalElements\":1");
            assertThat(status(one(session, cancelled))).as(role).isEqualTo(404);
        }
        assertThat(body(list(admin, ""))).contains("\"totalElements\":2");
        assertThat(body(list(admin, "?status=cancelled"))).contains("\"totalElements\":1");
        assertThat(status(one(admin, cancelled))).isEqualTo(200);
    }

    @Test
    void sinSesionSeRespondeUnauthorized() throws Exception {
        assertThat(status(mvc.perform(get("/classes")).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(post("/classes").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{}")).andReturn())).isEqualTo(401);
    }
}

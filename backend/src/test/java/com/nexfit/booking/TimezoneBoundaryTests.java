package com.nexfit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

/**
 * NEX-64 (TT-13): pruebas de borde de fecha y zona horaria. La base de datos no permite congelar {@code now()},
 * así que los bordes se fijan con instantes explícitos (funciones SQL y hora de inicio de la clase) y, para lo que
 * depende de "ahora", con gimnasios en zonas extremas (UTC+14 y UTC−11) cuyos días locales nunca coinciden.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TimezoneBoundaryTests {

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

    private Org org(String timezone) {
        Org org = fx.orgWithAdmin();
        jdbc.update("UPDATE organizations SET timezone = ? WHERE id = ?", timezone, org.id());
        return org;
    }

    private LocalDate localDate(Org org, String instant) {
        return jdbc.queryForObject("SELECT fn_org_local_date(?, CAST(? AS timestamptz))", LocalDate.class,
            org.id(), instant);
    }

    private long plan(long orgId) {
        return jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, ?, 50000, 30, true) RETURNING id
            """, Long.class, orgId, "Plan " + SEQ.incrementAndGet());
    }

    /** Cliente con membresía activa entre las fechas locales dadas (AAAA-MM-DD, ambas inclusivas). */
    private long clientWithMembership(Org org, String start, String end) {
        Account a = fx.user(false, true);
        long member = fx.member(a, org.id(), "client", true);
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status)
            VALUES (?, ?, ?, ?, CAST(? AS date), CAST(? AS date), 'active')
            """, org.id(), member, plan(org.id()), org.adminMemberId(), start, end);
        return member;
    }

    private long gymClass(Org org, String startsAt) {
        return jdbc.queryForObject("""
            INSERT INTO classes (organization_id, branch_id, name, starts_at, duration_minutes, capacity, status)
            VALUES (?, ?, 'Spinning', CAST(? AS timestamptz), 60, 10, 'scheduled') RETURNING id
            """, Long.class, org.id(), fx.branch(org.id()), startsAt);
    }

    private MvcResult send(Cookie session, String uri, String json) throws Exception {
        return mvc.perform(post(uri).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json)
            .cookie(session)).andReturn();
    }

    /** El Staff reserva la clase a nombre del cliente. */
    private MvcResult book(Org org, long classId, long memberId) throws Exception {
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        return send(staff, "/bookings", "{\"classId\":%d,\"memberId\":%d}".formatted(classId, memberId));
    }

    private MvcResult checkIn(Org org, long memberId) throws Exception {
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));
        return send(staff, "/check-ins", "{\"branchId\":%d,\"memberId\":%d}".formatted(fx.branch(org.id()), memberId));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private void assertNoValidMembership(MvcResult r) throws Exception {
        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r)).contains("\"code\":\"NO_VALID_MEMBERSHIP\"");
    }

    // ---- día local: función SQL en el límite de medianoche ---------------------------------

    @Test
    void bogotaCambiaDeDiaALasCincoUtcExactas() {
        Org org = org("America/Bogota");
        assertThat(localDate(org, "2031-11-10T04:59:59Z")).isEqualTo(LocalDate.parse("2031-11-09"));
        assertThat(localDate(org, "2031-11-10T05:00:00Z")).isEqualTo(LocalDate.parse("2031-11-10"));
    }

    @Test
    void tokioCambiaDeDiaALas1500UtcDelDiaAnterior() {
        Org org = org("Asia/Tokyo");
        assertThat(localDate(org, "2031-11-09T14:59:59Z")).isEqualTo(LocalDate.parse("2031-11-09"));
        assertThat(localDate(org, "2031-11-09T15:00:00Z")).isEqualTo(LocalDate.parse("2031-11-10"));
    }

    @Test
    void elMismoInstanteEsDiaDistintoEnGimnasiosDeZonasDistintas() {
        String instant = "2031-11-10T02:00:00Z";
        assertThat(localDate(org("America/Bogota"), instant)).isEqualTo(LocalDate.parse("2031-11-09"));
        assertThat(localDate(org("Asia/Tokyo"), instant)).isEqualTo(LocalDate.parse("2031-11-10"));
        assertThat(localDate(org("Pacific/Kiritimati"), instant)).isEqualTo(LocalDate.parse("2031-11-10"));
        assertThat(localDate(org("Pacific/Pago_Pago"), instant)).isEqualTo(LocalDate.parse("2031-11-09"));
    }

    @Test
    void elCambioDeHorarioDeVeranoNoMueveLaMedianocheLocal() {
        Org org = org("America/New_York");
        // Inicio del horario de verano (2031-03-09, 02:00 EST → 03:00 EDT): la medianoche del 10 es 04:00Z.
        assertThat(localDate(org, "2031-03-10T03:59:59Z")).isEqualTo(LocalDate.parse("2031-03-09"));
        assertThat(localDate(org, "2031-03-10T04:00:00Z")).isEqualTo(LocalDate.parse("2031-03-10"));
        // Fin del horario de verano (2031-11-02, 02:00 EDT → 01:00 EST): la medianoche del 3 es 05:00Z.
        assertThat(localDate(org, "2031-11-03T04:59:59Z")).isEqualTo(LocalDate.parse("2031-11-02"));
        assertThat(localDate(org, "2031-11-03T05:00:00Z")).isEqualTo(LocalDate.parse("2031-11-03"));
    }

    @Test
    void elAnoBisiestoTieneVeintinueveDeFebreroYElAnoComunNo() {
        Org org = org("America/Bogota");
        assertThat(localDate(org, "2032-02-29T12:00:00Z")).isEqualTo(LocalDate.parse("2032-02-29"));
        assertThat(localDate(org, "2032-03-01T04:59:59Z")).isEqualTo(LocalDate.parse("2032-02-29"));
        assertThat(localDate(org, "2031-03-01T04:59:59Z")).isEqualTo(LocalDate.parse("2031-02-28"));
    }

    // ---- vigencia de la membresía: inclusiva en ambos extremos -----------------------------

    @Test
    void laMembresiaEsVigenteEnSuPrimerYUltimoDiaYNoUnDiaAntesNiDespues() {
        Org org = org("America/Bogota");
        long member = clientWithMembership(org, "2031-03-10", "2031-03-20");
        String sql = "SELECT fn_has_valid_membership(?, CAST(? AS date))";
        assertThat(jdbc.queryForObject(sql, Boolean.class, member, "2031-03-09")).isFalse();
        assertThat(jdbc.queryForObject(sql, Boolean.class, member, "2031-03-10")).isTrue();
        assertThat(jdbc.queryForObject(sql, Boolean.class, member, "2031-03-20")).isTrue();
        assertThat(jdbc.queryForObject(sql, Boolean.class, member, "2031-03-21")).isFalse();
    }

    // ---- reservas: la vigencia se mide en el día local de la clase --------------------------

    @Test
    void laClaseDeLas2330LocalDelUltimoDiaDeVigenciaSeReserva() throws Exception {
        Org org = org("America/Bogota");
        long member = clientWithMembership(org, "2031-03-01", "2031-03-10");
        // 2031-03-11T04:30Z = 2031-03-10 23:30 en Bogotá: todavía es el último día de la membresía.
        long cls = gymClass(org, "2031-03-11T04:30:00Z");
        MvcResult r = book(org, cls, member);
        assertThat(status(r)).as(body(r)).isEqualTo(201);
    }

    @Test
    void laClaseDeLas0030LocalDelDiaSiguienteALaVigenciaSeRechaza() throws Exception {
        Org org = org("America/Bogota");
        long member = clientWithMembership(org, "2031-03-01", "2031-03-10");
        // 2031-03-11T05:30Z = 2031-03-11 00:30 en Bogotá: la membresía terminó ayer. En UTC sería aún el 11.
        long cls = gymClass(org, "2031-03-11T05:30:00Z");
        assertNoValidMembership(book(org, cls, member));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM class_bookings WHERE class_id = ?", Integer.class, cls))
            .isZero();
    }

    @Test
    void laClaseDeLaPrimeraHoraLocalDelPrimerDiaDeVigenciaSeReservaYLaDeLaUltimaHoraDelDiaAnteriorNo()
        throws Exception {
        Org org = org("America/Bogota");
        long member = clientWithMembership(org, "2031-03-10", "2031-03-20");
        // 2031-03-10T04:59:59Z = 2031-03-09 23:59:59 local → antes de empezar; 05:00:00Z = 00:00:00 del 10.
        assertNoValidMembership(book(org, gymClass(org, "2031-03-10T04:59:59Z"), member));
        MvcResult ok = book(org, gymClass(org, "2031-03-10T05:00:00Z"), member);
        assertThat(status(ok)).as(body(ok)).isEqualTo(201);
    }

    @Test
    void elGimnasioDeTokioMideLaVigenciaConSuPropiaZona() throws Exception {
        Org org = org("Asia/Tokyo");
        long member = clientWithMembership(org, "2031-03-01", "2031-03-10");
        // Tokio es UTC+9: el 10 local termina el 2031-03-10T15:00Z.
        MvcResult inside = book(org, gymClass(org, "2031-03-10T14:30:00Z"), member);
        assertThat(status(inside)).as(body(inside)).isEqualTo(201);
        // 2031-03-10T15:30Z ya es el 11 en Tokio, aunque en Bogotá sería todavía el 10.
        assertNoValidMembership(book(org, gymClass(org, "2031-03-10T15:30:00Z"), member));
    }

    @Test
    void laMismaClaseEsValidaParaUnGimnasioYRechazadaParaOtroSegunSuZona() throws Exception {
        String startsAt = "2031-03-11T03:00:00Z"; // 10-mar 22:00 en Bogotá · 11-mar 12:00 en Tokio
        Org bogota = org("America/Bogota");
        long inBogota = clientWithMembership(bogota, "2031-03-01", "2031-03-10");
        Org tokyo = org("Asia/Tokyo");
        long inTokyo = clientWithMembership(tokyo, "2031-03-01", "2031-03-10");
        MvcResult ok = book(bogota, gymClass(bogota, startsAt), inBogota);
        assertThat(status(ok)).as(body(ok)).isEqualTo(201);
        assertNoValidMembership(book(tokyo, gymClass(tokyo, startsAt), inTokyo));
    }

    // ---- ingreso: usa el "hoy" local del gimnasio (extremos UTC+14 y UTC−11) -----------------

    private LocalDate today(Org org) {
        return jdbc.queryForObject("SELECT fn_org_local_date(?, now())", LocalDate.class, org.id());
    }

    @Test
    void elMismoInstanteDaDiasLocalesDistintosEnLosExtremosHorarios() {
        Org kiritimati = org("Pacific/Kiritimati");
        Org pago = org("Pacific/Pago_Pago");
        // 25 horas de diferencia: el día local de Kiritimati siempre va 1 o 2 días por delante.
        long diff = today(pago).until(today(kiritimati), java.time.temporal.ChronoUnit.DAYS);
        assertThat(diff).isBetween(1L, 2L);
    }

    @Test
    void laMembresiaQueVenceHoyEnPagoPagoPermiteIngresoAllaYNoEnKiritimati() throws Exception {
        Org pago = org("Pacific/Pago_Pago");
        Org kiritimati = org("Pacific/Kiritimati");
        String pagoToday = today(pago).toString();

        long inPago = clientWithMembership(pago, pagoToday, pagoToday);
        long inKiritimati = clientWithMembership(kiritimati, pagoToday, pagoToday);

        MvcResult ok = checkIn(pago, inPago);
        assertThat(status(ok)).as(body(ok)).isEqualTo(201);
        // Para Kiritimati esa fecha ya pasó: la membresía está vencida.
        assertNoValidMembership(checkIn(kiritimati, inKiritimati));
    }

    @Test
    void laMembresiaQueEmpiezaHoyEnKiritimatiAunNoEmpezoEnPagoPago() throws Exception {
        Org pago = org("Pacific/Pago_Pago");
        Org kiritimati = org("Pacific/Kiritimati");
        String kiriToday = today(kiritimati).toString();

        long inKiritimati = clientWithMembership(kiritimati, kiriToday, kiriToday);
        long inPago = clientWithMembership(pago, kiriToday, kiriToday);

        MvcResult ok = checkIn(kiritimati, inKiritimati);
        assertThat(status(ok)).as(body(ok)).isEqualTo(201);
        // En Pago Pago todavía no llega esa fecha: la membresía está por comenzar.
        assertNoValidMembership(checkIn(pago, inPago));
    }
}

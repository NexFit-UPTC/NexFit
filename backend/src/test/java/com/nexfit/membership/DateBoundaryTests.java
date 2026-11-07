package com.nexfit.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
 * NEX-64 (TT-13, R-05, RN-27, RN-29, RN-32): vigencia de membresías, día de ingreso y validación de reservas en
 * 23:59 y 00:00 cuando la hora del servidor no es la del establecimiento. El día de una membresía se mide siempre en
 * la zona del establecimiento (fn_org_local_date), nunca en la del servidor.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DateBoundaryTests {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

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

    private long orgIn(String timezone) {
        return jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES ('Gym zona', 'g@nexfit.test', ?, true) RETURNING id
            """, Long.class, timezone);
    }

    private long plan(long orgId) {
        return jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, 'Plan zona', 50000, 30, true) RETURNING id
            """, Long.class, orgId);
    }

    /** Cliente con una membresía activa que va de {@code start} a {@code end}, ambos días incluidos. */
    private long memberWithMembership(long orgId, LocalDate start, LocalDate end) {
        Account a = fx.user(false, true);
        long memberId = fx.member(a, orgId, "client", true);
        long admin = fx.member(fx.user(false, true), orgId, "admin", true);
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status)
            VALUES (?, ?, ?, ?, ?, ?, 'active')
            """, orgId, memberId, plan(orgId), admin, start, end);
        return memberId;
    }

    /** ¿Es vigente la membresía en el instante dado? Con la zona de sesión del servidor que se indique. */
    private boolean validAt(long orgId, long memberId, Instant instant, String serverZone) {
        return jdbc.execute((Connection c) -> {
            try (Statement s = c.createStatement()) {
                s.execute("SET TIME ZONE '" + serverZone + "'");
            }
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT fn_has_valid_membership(?, fn_org_local_date(?, CAST(? AS timestamptz)))")) {
                p.setLong(1, memberId);
                p.setLong(2, orgId);
                p.setString(3, instant.toString());
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            } finally {
                try (Statement s = c.createStatement()) {
                    s.execute("RESET TIME ZONE");
                }
            }
        });
    }

    private LocalDate localDate(long orgId, Instant instant) {
        return jdbc.queryForObject("SELECT fn_org_local_date(?, CAST(? AS timestamptz))", LocalDate.class, orgId,
            instant.toString());
    }

    private static Instant at(LocalDate day, int hour, int minute, int second, ZoneId zone) {
        return ZonedDateTime.of(day, java.time.LocalTime.of(hour, minute, second), zone).toInstant();
    }

    @Test
    void laMembresiaVenceEnElDiaLocalAunqueElServidorEsteEnUtc() {
        long org = orgIn("America/Bogota");
        LocalDate day = LocalDate.of(2026, 11, 9);
        long member = memberWithMembership(org, day.minusDays(30), day);

        // 23:59:59 del día de vencimiento en Bogotá ya es el día siguiente en UTC (04:59:59Z del 10).
        Instant lastSecond = at(day, 23, 59, 59, BOGOTA);
        Instant midnight = at(day.plusDays(1), 0, 0, 0, BOGOTA);
        assertThat(lastSecond.toString()).isEqualTo("2026-11-10T04:59:59Z");

        for (String serverZone : new String[] {"UTC", "Asia/Tokyo", "America/Bogota"}) {
            assertThat(validAt(org, member, lastSecond, serverZone)).as("23:59:59 con servidor en " + serverZone)
                .isTrue();
            assertThat(validAt(org, member, midnight, serverZone)).as("00:00 con servidor en " + serverZone)
                .isFalse();
        }
        assertThat(localDate(org, lastSecond)).isEqualTo(day);
        assertThat(localDate(org, midnight)).isEqualTo(day.plusDays(1));
    }

    @Test
    void elPrimerDiaDeLaMembresiaEmpiezaALas0000LocalesYNoALas0000Utc() {
        long org = orgIn("America/Bogota");
        LocalDate start = LocalDate.of(2026, 11, 10);
        long member = memberWithMembership(org, start, start.plusDays(29));

        // 23:59:59 del día anterior en Bogotá todavía no es el inicio, aunque en UTC ya sea el 10 (04:59:59Z).
        assertThat(validAt(org, member, at(start.minusDays(1), 23, 59, 59, BOGOTA), "UTC")).isFalse();
        assertThat(validAt(org, member, at(start, 0, 0, 0, BOGOTA), "UTC")).isTrue();
    }

    @Test
    void cadaEstablecimientoSigueSuPropiaZonaHoraria() {
        long bogota = orgIn("America/Bogota");
        long tokyo = orgIn("Asia/Tokyo");
        Instant instant = Instant.parse("2026-11-09T15:30:00Z");

        // Misma marca de tiempo: 10:30 del 9 en Bogotá y 00:30 del 10 en Tokio.
        assertThat(localDate(bogota, instant)).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(localDate(tokyo, instant)).isEqualTo(LocalDate.of(2026, 11, 10));

        LocalDate day = LocalDate.of(2026, 11, 9);
        long inTokyo = memberWithMembership(tokyo, day.minusDays(30), day);
        long inBogota = memberWithMembership(bogota, day.minusDays(30), day);
        assertThat(validAt(tokyo, inTokyo, instant, "UTC")).as("en Tokio ya es el 10: vencida").isFalse();
        assertThat(validAt(bogota, inBogota, instant, "UTC")).as("en Bogotá sigue siendo el 9: vigente").isTrue();
        // Tokio, 23:59:59 del día de vencimiento (14:59:59Z) sigue vigente; 00:00 del siguiente no.
        assertThat(validAt(tokyo, inTokyo, at(day, 23, 59, 59, TOKYO), "UTC")).isTrue();
        assertThat(validAt(tokyo, inTokyo, at(day.plusDays(1), 0, 0, 0, TOKYO), "UTC")).isFalse();
    }

    @Test
    void laReservaValidaLaMembresiaConLaFechaLocalDeLaClase() throws Exception {
        long org = orgIn("America/Bogota");
        LocalDate end = LocalDate.now(BOGOTA).plusDays(5);
        long member = memberWithMembership(org, LocalDate.now(BOGOTA).minusDays(10), end);
        Account clientAccount = jdbc.queryForObject("""
            SELECT u.id, u.email FROM users u JOIN organization_members m ON m.user_id = u.id WHERE m.id = ?""",
            (rs, n) -> new Account(rs.getLong("id"), rs.getString("email")), member);
        var session = fx.login(clientAccount);
        long branch = fx.branch(org);
        // 23:30 del último día en Bogotá ya es el día siguiente en UTC: la reserva debe aceptarse.
        long lastNight = gymClass(org, branch, at(end, 23, 30, 0, BOGOTA));
        // 00:30 del día siguiente en Bogotá: la membresía ya venció ese día local; debe rechazarse.
        long nextMorning = gymClass(org, branch, at(end.plusDays(1), 0, 30, 0, BOGOTA));

        MvcResult ok = book(session, lastNight);
        MvcResult late = book(session, nextMorning);

        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        assertThat(late.getResponse().getStatus()).isEqualTo(409);
        assertThat(late.getResponse().getContentAsString()).contains("NO_VALID_MEMBERSHIP");
    }

    private long gymClass(long org, long branch, Instant startsAt) {
        return jdbc.queryForObject("""
            INSERT INTO classes (organization_id, branch_id, name, starts_at, duration_minutes, capacity, status)
            VALUES (?, ?, 'Zona', CAST(? AS timestamptz), 60, 10, 'scheduled') RETURNING id
            """, Long.class, org, branch, startsAt.toString());
    }

    private MvcResult book(jakarta.servlet.http.Cookie session, long classId) throws Exception {
        return mvc.perform(post("/bookings").with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"classId\":" + classId + "}")).andReturn();
    }
}

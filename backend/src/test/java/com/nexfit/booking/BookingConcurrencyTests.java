package com.nexfit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * NEX-63 (TT-12): 100 solicitudes simultáneas de reserva contra la misma clase. Amplía la prueba de 8 hilos de
 * {@link BookingTests}. La garantía la da la base de datos (RN-15): nunca hay más reservas confirmadas que cupos,
 * sin importar cuántas solicitudes lleguen a la vez.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class BookingConcurrencyTests {

    private static final int REQUESTS = 100;

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

    /** Cliente con una membresía vigente durante los próximos 60 días; devuelve su sesión. */
    private Cookie clientWithMembership(Org org, long planId) throws Exception {
        var account = fx.user(false, true);
        long memberId = fx.member(account, org.id(), "client", true);
        jdbc.update("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id, start_date, end_date,
                                     status)
            VALUES (?, ?, ?, ?, CURRENT_DATE - 1, CURRENT_DATE + 60, 'active')
            """, org.id(), memberId, planId, org.adminMemberId());
        return fx.login(account);
    }

    private long plan(long orgId) {
        return jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, 'Plan concurrencia', 50000, 30, true) RETURNING id
            """, Long.class, orgId);
    }

    private long futureClass(Org org, int capacity) {
        return jdbc.queryForObject("""
            INSERT INTO classes (organization_id, branch_id, name, starts_at, duration_minutes, capacity, status)
            VALUES (?, ?, 'Spinning', now() + interval '2 days', 60, ?, 'scheduled') RETURNING id
            """, Long.class, org.id(), fx.branch(org.id()), capacity);
    }

    private int confirmed(long classId) {
        return jdbc.queryForObject("SELECT count(*) FROM class_bookings WHERE class_id = ? AND status = 'confirmed'",
            Integer.class, classId);
    }

    /** Todas las solicitudes arrancan a la vez (compuerta); devuelve el código HTTP de cada una. */
    private List<Integer> fire(List<Cookie> sessions, long classId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(sessions.size());
        CountDownLatch ready = new CountDownLatch(sessions.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Cookie session : sessions) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(post("/bookings").with(csrf()).cookie(session)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"classId\":" + classId + "}"))
                    .andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();
        return statuses;
    }

    private static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    @Test
    void cienSolicitudesPorElUltimoCupoDejanExactamenteUnaReservaYElResultadoSeRepite() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        List<Cookie> sessions = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            sessions.add(clientWithMembership(org, plan));
        }

        for (int round = 1; round <= 3; round++) {
            long cls = futureClass(org, 1);

            List<Integer> statuses = fire(sessions, cls);

            assertThat(count(statuses, 201)).as("ronda %d: reservas creadas", round).isEqualTo(1);
            assertThat(count(statuses, 409)).as("ronda %d: rechazadas por cupo", round).isEqualTo(REQUESTS - 1);
            assertThat(confirmed(cls)).as("ronda %d: confirmadas en la base", round).isEqualTo(1);
        }
    }

    @Test
    void cienSolicitudesPorDiezCuposNuncaSuperanLaCapacidad() throws Exception {
        Org org = fx.orgWithAdmin();
        long plan = plan(org.id());
        List<Cookie> sessions = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            sessions.add(clientWithMembership(org, plan));
        }
        long cls = futureClass(org, 10);

        List<Integer> statuses = fire(sessions, cls);

        assertThat(count(statuses, 201)).isEqualTo(10);
        assertThat(count(statuses, 409)).isEqualTo(REQUESTS - 10);
        assertThat(confirmed(cls)).isEqualTo(10);
    }

    @Test
    void unMismoClienteQueReservaCienVecesALaVezQuedaConUnaSolaReserva() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie session = clientWithMembership(org, plan(org.id()));
        long cls = futureClass(org, 50);

        List<Integer> statuses = fire(java.util.Collections.nCopies(REQUESTS, session), cls);

        assertThat(count(statuses, 201)).isEqualTo(1);
        assertThat(count(statuses, 409)).isEqualTo(REQUESTS - 1);
        assertThat(confirmed(cls)).isEqualTo(1);
    }
}

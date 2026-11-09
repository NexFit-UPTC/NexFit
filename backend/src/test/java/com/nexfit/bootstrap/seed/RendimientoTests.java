package com.nexfit.bootstrap.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.bootstrap.seed.SeedService.Size;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * TT-14 (RNF-02): prueba de carga con el conjunto de referencia completo (5 gimnasios, 1 000 clientes y 20 000
 * ingresos por gimnasio) y 50 usuarios concurrentes. Umbrales validados por el docente (A-06, D-22): p95 de
 * consultas y registros de 2 s o menos, e indicadores del panel del Administrador de 5 s o menos.
 *
 * <p>Mide la aplicación y la base de datos reales (Testcontainers) sin la red; el informe se imprime en la salida de
 * la prueba y se resume en {@code docs/calidad/rendimiento.md}.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RendimientoTests {

    private static final String PASSWORD = "Clave-De-Prueba-123";
    private static final int GYMS = 5;
    private static final int USERS_PER_GYM = 10;
    private static final int ROUNDS = 6;
    private static final long P95_MAX_MS = 2_000;
    private static final long PANEL_MAX_MS = 5_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    SeedService seed;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    private record Gym(Cookie session, long branchId, List<Long> clients) { }

    private record Sample(String kind, long millis, int status) { }

    private Gym openGym(int g) throws Exception {
        MvcResult login = mvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"carga%d.admin@nexfit.test\",\"password\":\"%s\"}".formatted(g, PASSWORD)))
            .andReturn();
        assertThat(login.getResponse().getStatus()).as("login del Administrador del gimnasio " + g).isEqualTo(200);
        Cookie session = login.getResponse().getCookie("NEXFIT_SESSION");
        long org = jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", Long.class,
            "Gimnasio Carga " + g);
        long branch = jdbc.queryForObject("SELECT id FROM branches WHERE organization_id = ? LIMIT 1", Long.class, org);
        List<Long> clients = jdbc.queryForList("""
                SELECT member_id FROM member_roles WHERE organization_id = ? AND role = CAST('client' AS member_role)
                 ORDER BY member_id LIMIT ?
                """, Long.class, org, USERS_PER_GYM * ROUNDS);
        return new Gym(session, branch, clients);
    }

    private Sample timed(String kind, Callable<MvcResult> call) throws Exception {
        long start = System.nanoTime();
        MvcResult result = call.call();
        return new Sample(kind, (System.nanoTime() - start) / 1_000_000, result.getResponse().getStatus());
    }

    private List<Sample> virtualUser(Gym gym, int user) throws Exception {
        List<Sample> samples = new ArrayList<>();
        String today = LocalDate.now().toString();
        String lastWeek = LocalDate.now().minusDays(7).toString();
        for (int round = 0; round < ROUNDS; round++) {
            long client = gym.clients().get(user * ROUNDS + round);
            samples.add(timed("consulta: panel del Administrador", () -> mvc.perform(
                get("/dashboard/gym").cookie(gym.session())).andReturn()));
            samples.add(timed("consulta: ingresos de la última semana", () -> mvc.perform(
                get("/check-ins").param("from", lastWeek).param("to", today).cookie(gym.session())).andReturn()));
            samples.add(timed("consulta: pagos (página de 20)", () -> mvc.perform(
                get("/payments").param("size", "20").cookie(gym.session())).andReturn()));
            samples.add(timed("registro: ingreso", () -> mvc.perform(post("/check-ins").with(csrf())
                .cookie(gym.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":%d,\"memberId\":%d}".formatted(gym.branchId(), client))).andReturn()));
            samples.add(timed("registro: pago", () -> mvc.perform(post("/payments").with(csrf())
                .cookie(gym.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":%d,\"concept\":\"Mensualidad\",\"amount\":50000,\"method\":\"cash\"}"
                    .formatted(client))).andReturn()));
        }
        return samples;
    }

    private static long p95(List<Sample> samples) {
        List<Long> sorted = samples.stream().map(Sample::millis).sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    @Test
    void cincuentaUsuariosConcurrentesSobreElConjuntoDeReferenciaCumplenLosUmbrales() throws Exception {
        SeedService.Result loaded = seed.loadReference(PASSWORD, Size.REFERENCE_DEFAULT);
        assertThat(loaded.clients()).isEqualTo(5_000);
        assertThat(loaded.checkIns()).isEqualTo(100_000);

        List<Gym> gyms = new ArrayList<>();
        for (int g = 1; g <= GYMS; g++) {
            gyms.add(openGym(g));
        }

        ExecutorService pool = Executors.newFixedThreadPool(GYMS * USERS_PER_GYM);
        List<Future<List<Sample>>> futures = new ArrayList<>();
        for (Gym gym : gyms) {
            for (int u = 0; u < USERS_PER_GYM; u++) {
                int user = u;
                futures.add(pool.submit(() -> virtualUser(gym, user)));
            }
        }
        List<Sample> all = Collections.synchronizedList(new ArrayList<>());
        for (Future<List<Sample>> future : futures) {
            all.addAll(future.get());
        }
        pool.shutdown();

        StringBuilder report = new StringBuilder("\n=== TT-14 · 50 usuarios concurrentes, conjunto de referencia ===\n");
        all.stream().map(Sample::kind).distinct().sorted().forEach(kind -> {
            List<Sample> of = all.stream().filter(s -> s.kind().equals(kind)).toList();
            long max = of.stream().mapToLong(Sample::millis).max().orElse(0);
            report.append("%-45s n=%4d  p95=%5d ms  máx=%5d ms%n".formatted(kind, of.size(), p95(of), max));
        });
        System.out.println(report);

        assertThat(all.stream().filter(s -> s.status() >= 400).map(s -> s.kind() + " → " + s.status()).distinct()
            .toList()).as("ninguna petición de la carga debe fallar").isEmpty();
        assertThat(p95(all)).as("p95 de consultas y registros").isLessThanOrEqualTo(P95_MAX_MS);
        assertThat(all.stream().filter(s -> s.kind().contains("panel")).mapToLong(Sample::millis).max().orElse(0))
            .as("panel del Administrador").isLessThanOrEqualTo(PANEL_MAX_MS);
    }
}

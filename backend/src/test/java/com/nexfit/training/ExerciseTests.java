package com.nexfit.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.nexfit.shared.AccessFixtures;
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
import org.springframework.dao.DataIntegrityViolationException;
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

/** NEX-41 (US-41): catálogo global del Master y ejercicios propios de cada gimnasio. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ExerciseTests {

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

    private Cookie master() throws Exception {
        return fx.login(fx.user(true, true));
    }

    private static String unique(String base) {
        return base + " " + SEQ.incrementAndGet();
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

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static long idOf(String json) {
        return Long.parseLong(json.replaceAll("(?s)^\\{\"id\":(\\d+).*", "$1"));
    }

    private long create(Cookie session, String name, String extra) throws Exception {
        MvcResult r = send(session, "POST", "/exercises", "{\"name\":\"%s\"%s}".formatted(name, extra));
        assertThat(status(r)).as(body(r)).isEqualTo(201);
        return idOf(body(r));
    }

    // ---- crear ---------------------------------------------------------------------------

    @Test
    void elMasterCreaUnEjercicioGlobalQueVenTodosLosGimnasios() throws Exception {
        Org a = fx.orgWithAdmin();
        Org b = fx.orgWithAdmin();
        String name = unique("Sentadilla global");

        MvcResult r = send(master(), "POST", "/exercises", ("{\"name\":\"%s\",\"muscleGroup\":\"Piernas\","
            + "\"description\":\"Base\",\"imageUrl\":\"/img/ejercicios/sentadilla.svg\"}").formatted(name));

        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r)).contains("\"scope\":\"global\"").contains("\"name\":\"" + name + "\"")
            .contains("\"imageUrl\":\"/img/ejercicios/sentadilla.svg\"").contains("\"active\":true");
        assertThat(jdbc.queryForObject("SELECT organization_id IS NULL FROM exercises WHERE id = ?", Boolean.class,
            idOf(body(r)))).isTrue();
        for (Org org : new Org[] {a, b}) {
            assertThat(body(send(fx.login(org.admin()), "GET", "/exercises?q=" + name.replace(" ", "%20"), null)))
                .contains(name);
        }
    }

    @Test
    void unNombreGlobalRepetidoSinDistinguirMayusculasSeRechaza() throws Exception {
        Cookie master = master();
        String name = unique("Press banca");
        create(master, name, "");

        MvcResult dup = send(master, "POST", "/exercises", "{\"name\":\"%s\"}".formatted(name.toUpperCase()));

        assertThat(status(dup)).isEqualTo(409);
        assertThat(body(dup)).contains("EXERCISE_NAME_TAKEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercises WHERE organization_id IS NULL "
            + "AND lower(name) = lower(?)", Integer.class, name)).isEqualTo(1);
    }

    @Test
    void dosCreacionesSimultaneasDelMismoNombreGlobalDejanUnaSola() throws Exception {
        Cookie master = master();
        String name = unique("Dominadas");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return status(send(master, "POST", "/exercises", "{\"name\":\"%s\"}".formatted(name)));
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
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercises WHERE organization_id IS NULL "
            + "AND lower(name) = lower(?)", Integer.class, name)).isEqualTo(1);
    }

    @Test
    void elAdministradorYElEntrenadorCreanEjerciciosPropiosAunConElNombreDeUnoGlobal() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        String name = unique("Remo con barra");
        create(master(), name, "");
        Cookie admin = fx.login(org.admin());
        Cookie trainer = fx.login(fx.actor(org.id(), "trainer"));

        long own = create(admin, name, ",\"muscleGroup\":\"Espalda\"");
        MvcResult dupOwn = send(trainer, "POST", "/exercises", "{\"name\":\"%s\"}".formatted(name.toLowerCase()));
        long byTrainer = create(trainer, unique("Remo del entrenador"), "");

        assertThat(dupOwn.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT organization_id FROM exercises WHERE id = ?", Long.class, own))
            .isEqualTo(org.id());
        assertThat(jdbc.queryForObject("SELECT organization_id FROM exercises WHERE id = ?", Long.class, byTrainer))
            .isEqualTo(org.id());
        // Otro gimnasio puede tener uno propio con el mismo nombre y no ve los de este.
        create(fx.login(other.admin()), name, "");
        assertThat(body(send(fx.login(other.admin()), "GET", "/exercises?scope=own", null)))
            .doesNotContain("Remo del entrenador").contains(name);
    }

    @Test
    void staffYClienteSoloLeen() throws Exception {
        Org org = fx.orgWithAdmin();
        long id = create(fx.login(org.admin()), unique("Plancha"), "");
        for (String role : new String[] {"staff", "client"}) {
            Cookie session = fx.login(fx.actor(org.id(), role));
            assertThat(status(send(session, "GET", "/exercises/" + id, null))).as(role).isEqualTo(200);
            assertThat(status(send(session, "POST", "/exercises", "{\"name\":\"X\"}"))).as(role).isEqualTo(403);
            assertThat(status(send(session, "PUT", "/exercises/" + id, "{\"name\":\"X\"}"))).as(role).isEqualTo(403);
        }
        Cookie trainer = fx.login(fx.actor(org.id(), "trainer"));
        assertThat(status(send(trainer, "PUT", "/exercises/" + id, "{\"name\":\"X\"}"))).isEqualTo(403);
    }

    @Test
    void validaLosCampos() throws Exception {
        Cookie admin = fx.login(fx.orgWithAdmin().admin());

        assertThat(status(send(admin, "POST", "/exercises", "{}"))).isEqualTo(400);
        assertThat(status(send(admin, "POST", "/exercises", "{\"name\":\"   \"}"))).isEqualTo(400);
        assertThat(status(send(admin, "POST", "/exercises", "{\"name\":\"" + "x".repeat(101) + "\"}"))).isEqualTo(400);
        assertThat(status(send(admin, "POST", "/exercises", "{\"name\":\"A\",\"muscleGroup\":5}"))).isEqualTo(400);
        assertThat(status(send(admin, "POST", "/exercises", "{\"name\":\"A\",\"imageUrl\":\"ftp://x\"}")))
            .isEqualTo(400);
        assertThat(status(send(admin, "GET", "/exercises?scope=otro", null))).isEqualTo(400);
    }

    // ---- modificar -----------------------------------------------------------------------

    @Test
    void elAdministradorModificaLosPropiosPeroNoLosGlobalesNiLosDeOtroGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        long own = create(admin, unique("Curl"), "");
        long other1 = create(admin, unique("Curl martillo"), "");
        long global = create(master(), unique("Fondos"), "");
        long foreign = create(fx.login(other.admin()), unique("Ajeno"), "");
        String newName = unique("Curl de bíceps");

        MvcResult ok = send(admin, "PUT", "/exercises/" + own,
            "{\"name\":\"%s\",\"muscleGroup\":\"Brazos\"}".formatted(newName));
        MvcResult dup = send(admin, "PUT", "/exercises/" + other1, "{\"name\":\"%s\"}".formatted(newName.toUpperCase()));
        MvcResult glob = send(admin, "PUT", "/exercises/" + global, "{\"name\":\"Hackeado\"}");
        MvcResult alien = send(admin, "PUT", "/exercises/" + foreign, "{\"name\":\"Hackeado\"}");

        assertThat(status(ok)).isEqualTo(200);
        assertThat(body(ok)).contains("\"name\":\"" + newName + "\"").contains("\"muscleGroup\":\"Brazos\"");
        assertThat(status(dup)).isEqualTo(409);
        assertThat(status(glob)).isEqualTo(403);
        assertThat(body(glob)).contains("EXERCISE_GLOBAL_READ_ONLY");
        assertThat(status(alien)).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT name FROM exercises WHERE id = ?", String.class, foreign))
            .startsWith("Ajeno");
    }

    @Test
    void elMasterModificaLosGlobalesPeroNoLosPropiosDeUnGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie master = master();
        long global = create(master, unique("Peso muerto"), "");
        long own = create(fx.login(org.admin()), unique("Propio"), "");
        String renamed = unique("Peso muerto rumano");

        MvcResult ok = send(master, "PUT", "/exercises/" + global, "{\"name\":\"%s\"}".formatted(renamed));
        MvcResult notHis = send(master, "PUT", "/exercises/" + own, "{\"name\":\"Hackeado\"}");

        assertThat(status(ok)).isEqualTo(200);
        assertThat(status(notHis)).isEqualTo(404);
        assertThat(status(send(master, "GET", "/exercises/" + own, null))).isEqualTo(404);
    }

    // ---- activar y consultar ---------------------------------------------------------------

    @Test
    void soloElMasterDesactivaYUnGlobalInactivoDejaDeVerseEnLosGimnasios() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie master = master();
        Cookie admin = fx.login(org.admin());
        String name = unique("Zancadas");
        long id = create(master, name, "");

        assertThat(status(send(admin, "PATCH", "/exercises/" + id + "/active", "{\"active\":false}"))).isEqualTo(403);
        MvcResult off = send(master, "PATCH", "/exercises/" + id + "/active", "{\"active\":false}");

        assertThat(status(off)).isEqualTo(200);
        assertThat(body(off)).contains("\"active\":false");
        assertThat(body(send(admin, "GET", "/exercises?q=" + name.replace(" ", "%20"), null))).doesNotContain(name);
        assertThat(status(send(admin, "GET", "/exercises/" + id, null))).isEqualTo(404);
        assertThat(body(send(master, "GET", "/exercises?active=false&q=" + name.replace(" ", "%20"), null)))
            .contains(name);
        assertThat(status(send(master, "PATCH", "/exercises/" + id + "/active", "{}"))).isEqualTo(400);

        send(master, "PATCH", "/exercises/" + id + "/active", "{\"active\":true}");
        assertThat(body(send(admin, "GET", "/exercises?q=" + name.replace(" ", "%20"), null))).contains(name);
    }

    @Test
    void elMasterSoloVeElCatalogoGlobal() throws Exception {
        Org org = fx.orgWithAdmin();
        String own = unique("Solo del gimnasio");
        create(fx.login(org.admin()), own, "");

        assertThat(body(send(master(), "GET", "/exercises", null))).doesNotContain(own);
    }

    @Test
    void filtraPorNombreGrupoMuscularYAmbitoYLosComodinesNoSeInterpretan() throws Exception {
        Org org = fx.orgWithAdmin();
        Cookie admin = fx.login(org.admin());
        String tag = "Zq" + SEQ.incrementAndGet();
        create(admin, tag + " Press militar", ",\"muscleGroup\":\"Hombros\"");
        create(admin, tag + " Elevaciones", ",\"muscleGroup\":\"Hombros\"");
        create(admin, tag + " Remo", ",\"muscleGroup\":\"Espalda\"");

        String byName = body(send(admin, "GET", "/exercises?q=" + tag.toLowerCase() + "%20press", null));
        String byGroup = body(send(admin, "GET", "/exercises?scope=own&muscleGroup=hombros", null));
        String wildcard = body(send(admin, "GET", "/exercises?q=%25", null));
        String underscore = body(send(admin, "GET", "/exercises?q=" + tag + "_Remo", null));

        assertThat(byName).contains("Press militar").doesNotContain("Elevaciones");
        assertThat(byGroup).contains("Press militar").contains("Elevaciones").doesNotContain("Remo");
        assertThat(wildcard).isEqualTo("[]");
        assertThat(underscore).isEqualTo("[]");
    }

    @Test
    void laBaseRechazaDireccionesDeImagenInvalidas() {
        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
            () -> jdbc.update("""
                INSERT INTO exercises (name, is_active, image_url) VALUES (?, true, 'data:text/html,x')""",
                unique("Mala imagen")));
        assertThat(e.getMessage()).contains("ck_exercises_image_url");
    }
}

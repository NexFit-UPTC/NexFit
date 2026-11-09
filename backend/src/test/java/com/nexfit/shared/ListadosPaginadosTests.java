package com.nexfit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * BE-12 (NEX-106): GET /audit/events y GET /staff responden una página ({@code content, page, size,
 * totalElements, totalPages}) con tamaño por defecto 20, tope 50 y un orden estable entre páginas.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ListadosPaginadosTests {

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

    private String json(Cookie session, String uri) throws Exception {
        MvcResult r = mvc.perform(get(uri).cookie(session)).andReturn();
        assertThat(r.getResponse().getStatus()).as(uri).isEqualTo(200);
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static int num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).intValue();
    }

    private static List<Integer> ints(String json, String path) {
        List<Number> raw = JsonPath.read(json, path);
        return raw.stream().map(Number::intValue).toList();
    }

    /** {@code count} eventos del actor, uno por minuto, con entity_id = 1..count (el más nuevo = count). */
    private void events(long orgId, long actorId, String type, int count) {
        jdbc.update("""
            INSERT INTO audit_events (organization_id, actor_user_id, event_type, entity_type, entity_id,
                                      occurred_at, metadata)
            SELECT ?, ?, ?, 'user', g, TIMESTAMPTZ '2026-01-01 00:00:00+00' + g * interval '1 minute', '{}'::jsonb
              FROM generate_series(1, ?) g
            """, orgId, actorId, type, count);
    }

    private String audit(Cookie admin, long actorId, String extra) throws Exception {
        return json(admin, "/audit/events?userId=" + actorId + extra);
    }

    // ---- auditoría ---------------------------------------------------------------------------

    @Test
    void laAuditoriaPorDefectoTrae20DeLosMasRecientesConElTotal() throws Exception {
        Org org = fx.orgWithAdmin();
        Account actor = fx.user(false, true);
        events(org.id(), actor.id(), "PAYMENT_VOIDED", 55);
        Cookie admin = fx.login(org.admin());

        String body = audit(admin, actor.id(), "");

        assertThat(num(body, "$.page")).isZero();
        assertThat(num(body, "$.size")).isEqualTo(20);
        assertThat(num(body, "$.totalElements")).isEqualTo(55);
        assertThat(num(body, "$.totalPages")).isEqualTo(3);
        assertThat(ints(body, "$.content[*].entityId")).hasSize(20).startsWith(55, 54).endsWith(37, 36);
    }

    @Test
    void laUltimaPaginaTraeElResto() throws Exception {
        Org org = fx.orgWithAdmin();
        Account actor = fx.user(false, true);
        events(org.id(), actor.id(), "PAYMENT_VOIDED", 55);
        Cookie admin = fx.login(org.admin());

        String body = audit(admin, actor.id(), "&page=2&size=20");

        assertThat(num(body, "$.page")).isEqualTo(2);
        assertThat(ints(body, "$.content[*].entityId")).hasSize(15).startsWith(15).endsWith(1);
    }

    @Test
    void elTamanoSeAcotaA50YLosValoresNoValidosVuelvenALosPorDefecto() throws Exception {
        Org org = fx.orgWithAdmin();
        Account actor = fx.user(false, true);
        events(org.id(), actor.id(), "PAYMENT_VOIDED", 55);
        Cookie admin = fx.login(org.admin());

        String grande = audit(admin, actor.id(), "&size=1000");
        assertThat(num(grande, "$.size")).isEqualTo(50);
        assertThat(ints(grande, "$.content[*].entityId")).hasSize(50);
        assertThat(num(grande, "$.totalPages")).isEqualTo(2);

        assertThat(num(audit(admin, actor.id(), "&size=0"), "$.size")).isEqualTo(20);
        assertThat(num(audit(admin, actor.id(), "&size=-5"), "$.size")).isEqualTo(20);
        assertThat(num(audit(admin, actor.id(), "&page=-3"), "$.page")).isZero();
    }

    @Test
    void unaPaginaMasAllaDelFinalVienVaciaConElTotalIntacto() throws Exception {
        Org org = fx.orgWithAdmin();
        Account actor = fx.user(false, true);
        events(org.id(), actor.id(), "PAYMENT_VOIDED", 5);
        Cookie admin = fx.login(org.admin());

        String body = audit(admin, actor.id(), "&page=99");

        assertThat(ints(body, "$.content[*].entityId")).isEmpty();
        assertThat(num(body, "$.totalElements")).isEqualTo(5);
        assertThat(num(body, "$.totalPages")).isEqualTo(1);
    }

    @Test
    void conVariosEventosEnElMismoInstanteElOrdenNoRepiteNiSaltaEntrePaginas() throws Exception {
        Org org = fx.orgWithAdmin();
        Account actor = fx.user(false, true);
        jdbc.update("""
            INSERT INTO audit_events (organization_id, actor_user_id, event_type, entity_type, entity_id,
                                      occurred_at, metadata)
            SELECT ?, ?, 'PAYMENT_VOIDED', 'user', g, TIMESTAMPTZ '2026-01-01 00:00:00+00', '{}'::jsonb
              FROM generate_series(1, 10) g
            """, org.id(), actor.id());
        Cookie admin = fx.login(org.admin());

        List<Integer> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            seen.addAll(ints(audit(admin, actor.id(), "&size=4&page=" + page), "$.content[*].entityId"));
        }

        assertThat(seen).hasSize(10).doesNotHaveDuplicates().containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void losFiltrosSeConservanAlPaginarYElTotalLosRespeta() throws Exception {
        Org org = fx.orgWithAdmin();
        Account actor = fx.user(false, true);
        events(org.id(), actor.id(), "PAYMENT_VOIDED", 30);
        events(org.id(), actor.id(), "LOGIN", 7);
        Cookie admin = fx.login(org.admin());

        String login = audit(admin, actor.id(), "&type=LOGIN&size=5");
        assertThat(num(login, "$.totalElements")).isEqualTo(7);
        assertThat(num(login, "$.totalPages")).isEqualTo(2);
        assertThat(ints(login, "$.content[*].entityId")).hasSize(5);

        String todos = audit(admin, actor.id(), "");
        assertThat(num(todos, "$.totalElements")).isEqualTo(37);
    }

    @Test
    void elAdminSoloCuentaLosEventosDeSuGimnasio() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Account actor = fx.user(false, true);
        events(org.id(), actor.id(), "PAYMENT_VOIDED", 6);
        events(other.id(), actor.id(), "PAYMENT_VOIDED", 9);

        String body = audit(fx.login(org.admin()), actor.id(), "");

        assertThat(num(body, "$.totalElements")).isEqualTo(6);
    }

    @Test
    void elMasterPaginaTodaLaPlataforma() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        Account actor = fx.user(false, true);
        events(org.id(), actor.id(), "PAYMENT_VOIDED", 6);
        events(other.id(), actor.id(), "PAYMENT_VOIDED", 9);
        Cookie master = fx.login(fx.user(true, true));

        String body = audit(master, actor.id(), "&size=10");

        assertThat(num(body, "$.totalElements")).isEqualTo(15);
        assertThat(num(body, "$.totalPages")).isEqualTo(2);
        assertThat(ints(body, "$.content[*].entityId")).hasSize(10);
    }

    // ---- personal ------------------------------------------------------------------------------

    /** Administrador + 25 de Staff + 3 entrenadores = 29 de personal; además 10 clientes (no cuentan). */
    private Org orgWithTeam() {
        Org org = fx.orgWithAdmin();
        for (int i = 0; i < 25; i++) {
            fx.actor(org.id(), "staff");
        }
        for (int i = 0; i < 3; i++) {
            fx.actor(org.id(), "trainer");
        }
        for (int i = 0; i < 10; i++) {
            fx.actor(org.id(), "client");
        }
        return org;
    }

    @Test
    void elPersonalPorDefectoTrae20ConElTotalYSinClientes() throws Exception {
        Org org = orgWithTeam();

        String body = json(fx.login(org.admin()), "/staff");

        assertThat(num(body, "$.page")).isZero();
        assertThat(num(body, "$.size")).isEqualTo(20);
        assertThat(num(body, "$.totalElements")).isEqualTo(29);
        assertThat(num(body, "$.totalPages")).isEqualTo(2);
        assertThat(ints(body, "$.content[*].memberId")).hasSize(20);
    }

    @Test
    void elTamanoDelPersonalSeAcotaA50() throws Exception {
        Org org = orgWithTeam();
        Cookie admin = fx.login(org.admin());

        String grande = json(admin, "/staff?size=1000");

        assertThat(num(grande, "$.size")).isEqualTo(50);
        assertThat(ints(grande, "$.content[*].memberId")).hasSize(29);
        assertThat(num(json(admin, "/staff?size=0"), "$.size")).isEqualTo(20);
        assertThat(num(json(admin, "/staff?page=-1"), "$.page")).isZero();
    }

    @Test
    void recorrerElPersonalPorPaginasNoRepiteNiOmiteANadie() throws Exception {
        Org org = orgWithTeam();
        Cookie admin = fx.login(org.admin());

        List<Integer> ids = new ArrayList<>();
        List<String> orden = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = json(admin, "/staff?size=10&page=" + page);
            ids.addAll(ints(body, "$.content[*].memberId"));
            List<String> last = JsonPath.read(body, "$.content[*].lastName");
            List<String> first = JsonPath.read(body, "$.content[*].firstName");
            for (int i = 0; i < last.size(); i++) {
                orden.add(last.get(i) + "|" + first.get(i));
            }
        }

        Set<Integer> unicos = new HashSet<>(ids);
        assertThat(ids).hasSize(29);
        assertThat(unicos).hasSize(29);
        // Desempate estable: a igual apellido y nombre, el miembro de menor id va primero, también entre páginas.
        for (int i = 1; i < ids.size(); i++) {
            if (orden.get(i).equals(orden.get(i - 1))) {
                assertThat(ids.get(i)).as("posición " + i).isGreaterThan(ids.get(i - 1));
            }
        }
    }

    @Test
    void elPersonalSeFiltraPorRolYLosClientesTienenSuPropioFiltro() throws Exception {
        Org org = orgWithTeam();
        Cookie admin = fx.login(org.admin());

        assertThat(num(json(admin, "/staff?role=trainer"), "$.totalElements")).isEqualTo(3);
        assertThat(num(json(admin, "/staff?role=TRAINER"), "$.totalElements")).isEqualTo(3);
        assertThat(num(json(admin, "/staff?role=staff"), "$.totalElements")).isEqualTo(25);
        assertThat(num(json(admin, "/staff?role=admin"), "$.totalElements")).isEqualTo(1);
        assertThat(num(json(admin, "/staff?role=client"), "$.totalElements")).isEqualTo(10);
    }

    @Test
    void unRolDesconocidoEnElPersonalResponde400() throws Exception {
        Org org = fx.orgWithAdmin();

        MvcResult r = mvc.perform(get("/staff?role=hacker").cookie(fx.login(org.admin()))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"VALIDATION_ERROR\"", "\"field\":\"role\"");
    }

    @Test
    void elPersonalDeOtroGimnasioNoSeCuenta() throws Exception {
        Org org = orgWithTeam();
        Org other = fx.orgWithAdmin();
        fx.actor(other.id(), "staff");

        String body = json(fx.login(org.admin()), "/staff");

        assertThat(num(body, "$.totalElements")).isEqualTo(29);
    }
}

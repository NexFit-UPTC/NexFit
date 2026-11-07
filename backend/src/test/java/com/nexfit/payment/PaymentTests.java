package com.nexfit.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.ReglaDeNegocio;
import com.nexfit.shared.AccessFixtures;
import com.nexfit.shared.AccessFixtures.Account;
import com.nexfit.shared.AccessFixtures.Org;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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

/** NEX-45 (US-26), NEX-51 (US-28), NEX-50 (US-27) y NEX-54 (US-50): registrar, consultar, anular y completar pagos. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PaymentTests {

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

    private record Client(Account account, long memberId) { }

    private Client client(long orgId) {
        Account a = fx.user(false, true);
        return new Client(a, fx.member(a, orgId, "client", true));
    }

    private static String json(long memberId, String amount) {
        return "{\"memberId\":%d,\"concept\":\"Mensualidad\",\"amount\":%s,\"method\":\"cash\"}"
            .formatted(memberId, amount);
    }

    private MvcResult register(Cookie session, String json) throws Exception {
        return mvc.perform(post("/payments").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult list(Cookie session, String query) throws Exception {
        return mvc.perform(get("/payments" + query).cookie(session)).andReturn();
    }

    private MvcResult voidIt(Cookie session, long id, String body) throws Exception {
        return mvc.perform(patch("/payments/" + id + "/void").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult complete(Cookie session, long id) throws Exception {
        return mvc.perform(patch("/payments/" + id + "/complete").with(csrf()).cookie(session)).andReturn();
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

    private long plan(long orgId) {
        return jdbc.queryForObject("""
            INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
            VALUES (?, 'Mensual', 50000, 30, true) RETURNING id
            """, Long.class, orgId);
    }

    private long membership(Org org, long planId, long memberId) {
        return jdbc.queryForObject("""
            INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id,
                                     start_date, end_date, status)
            VALUES (?, ?, ?, ?, DATE '2026-10-01', DATE '2026-10-31', 'active') RETURNING id
            """, Long.class, org.id(), memberId, planId, org.adminMemberId());
    }

    private long payment(long orgId, long memberId, long registeredBy, String amount, String status) {
        return jdbc.queryForObject("""
            INSERT INTO payments (organization_id, member_id, registered_by_member_id, concept, amount, paid_at,
                                  method, status, void_reason, completed_at, completed_by_member_id)
            VALUES (?, ?, ?, 'Mensualidad', CAST(? AS numeric), now(), 'cash', CAST(? AS payment_status),
                    CASE WHEN ? = 'voided' THEN 'Error de digitación' END,
                    CASE WHEN ? = 'completed' THEN now() END,
                    CASE WHEN ? = 'completed' THEN CAST(? AS bigint) END) RETURNING id
            """, Long.class, orgId, memberId, registeredBy, amount, status, status, status, status, registeredBy);
    }

    private int paymentCount(long orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE organization_id = ?", Integer.class, orgId);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
            SELECT organization_id, member_id, membership_id, registered_by_member_id, amount,
                   CAST(status AS text) AS status, void_reason, completed_at, completed_by_member_id
            FROM payments WHERE id = ?""", id);
    }

    // ---- US-26: registrar ----------------------------------------------------------------

    // El Staff registra un pago completado: queda asociado al cliente y al Staff que lo registró.
    @Test
    void elStaffRegistraUnPagoCompletadoAsociadoAlCliente() throws Exception {
        Org org = fx.orgWithAdmin();
        Client client = client(org.id());
        Account staff = fx.actor(org.id(), "staff");

        MvcResult r = register(fx.login(staff), json(client.memberId(), "50000"));

        assertThat(status(r)).isEqualTo(201);
        Map<String, Object> row = row(idOf(body(r)));
        assertThat(row).containsEntry("organization_id", org.id()).containsEntry("member_id", client.memberId())
            .containsEntry("status", "completed");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("50000");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("completed_by_member_id")).isEqualTo(row.get("registered_by_member_id"));
    }

    // Un pago pendiente se acepta sin fecha ni autor de finalización.
    @Test
    void unPagoPendienteSeRegistraSinFechaDeFinalizacion() throws Exception {
        Org org = fx.orgWithAdmin();
        Client client = client(org.id());

        MvcResult r = register(fx.login(fx.actor(org.id(), "staff")),
            "{\"memberId\":%d,\"concept\":\"Transferencia\",\"amount\":80000,\"method\":\"transfer\",\"status\":\"pending\"}"
                .formatted(client.memberId()));

        assertThat(status(r)).isEqualTo(201);
        Map<String, Object> row = row(idOf(body(r)));
        assertThat(row).containsEntry("status", "pending");
        assertThat(row.get("completed_at")).isNull();
    }

    // Valor 0 o negativo, método inválido o cliente inexistente se rechazan y no se crea nada.
    @Test
    void valorNoPositivoMetodoInvalidoOClienteInexistenteSeRechazan() throws Exception {
        Org org = fx.orgWithAdmin();
        Client client = client(org.id());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        for (String amount : new String[] {"0", "-5", "10.555", "\"abc\""}) {
            MvcResult r = register(staff, json(client.memberId(), amount));
            assertThat(status(r)).as(amount).isEqualTo(400);
            assertThat(body(r)).contains("\"field\":\"amount\"");
        }
        MvcResult method = register(staff, json(client.memberId(), "1000").replace("cash", "bitcoin"));
        assertThat(status(method)).isEqualTo(400);
        assertThat(body(method)).contains("\"field\":\"method\"");
        MvcResult unknown = register(staff, json(999999, "1000"));
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown)).contains("\"field\":\"memberId\"");

        assertThat(paymentCount(org.id())).isZero();
    }

    // Una membresía de otro cliente se rechaza; sin membresía o con la propia se acepta.
    @Test
    void laMembresiaDebePertenecerAlMismoCliente() throws Exception {
        Org org = fx.orgWithAdmin();
        Client ana = client(org.id());
        Client luis = client(org.id());
        long plan = plan(org.id());
        long anaMembership = membership(org, plan, ana.memberId());
        Cookie staff = fx.login(fx.actor(org.id(), "staff"));

        MvcResult wrong = register(staff, "{\"memberId\":%d,\"membershipId\":%d,\"concept\":\"X\",\"amount\":1000,\"method\":\"cash\"}"
            .formatted(luis.memberId(), anaMembership));
        assertThat(status(wrong)).isEqualTo(400);
        assertThat(body(wrong)).contains("\"field\":\"membershipId\"");

        MvcResult own = register(staff, "{\"memberId\":%d,\"membershipId\":%d,\"concept\":\"X\",\"amount\":1000,\"method\":\"cash\"}"
            .formatted(ana.memberId(), anaMembership));
        assertThat(status(own)).isEqualTo(201);
        assertThat(row(idOf(body(own)))).containsEntry("membership_id", anaMembership);
    }

    // Solo el Staff registra: Administrador, Entrenador y Cliente reciben 403.
    @Test
    void soloElStaffRegistraPagos() throws Exception {
        Org org = fx.orgWithAdmin();
        Client client = client(org.id());

        assertThat(status(register(fx.login(org.admin()), json(client.memberId(), "1000")))).isEqualTo(403);
        assertThat(status(register(fx.login(fx.actor(org.id(), "trainer")), json(client.memberId(), "1000"))))
            .isEqualTo(403);
        assertThat(status(register(fx.login(client.account()), json(client.memberId(), "1000")))).isEqualTo(403);
        assertThat(paymentCount(org.id())).isZero();
    }

    // RN-31: no existe la operación de borrar un pago.
    @Test
    @ReglaDeNegocio("RN-19")
    void noExisteLaOperacionDeBorrarUnPago() throws Exception {
        Org org = fx.orgWithAdmin();
        Client client = client(org.id());
        Account staff = fx.actor(org.id(), "staff");
        long id = payment(org.id(), client.memberId(), org.adminMemberId(), "1000", "completed");

        MvcResult r = mvc.perform(delete("/payments/" + id).with(csrf()).cookie(fx.login(staff))).andReturn();

        assertThat(status(r)).isIn(403, 404, 405);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    // ---- US-28: consultar ----------------------------------------------------------------

    // El Cliente ve solo los suyos (anulados incluidos); el total no suma los anulados.
    @Test
    void elClienteVeSoloSusPagosYElTotalNoIncluyeAnulados() throws Exception {
        Org org = fx.orgWithAdmin();
        Client ana = client(org.id());
        Client luis = client(org.id());
        payment(org.id(), ana.memberId(), org.adminMemberId(), "50000", "completed");
        payment(org.id(), ana.memberId(), org.adminMemberId(), "30000", "voided");
        payment(org.id(), luis.memberId(), org.adminMemberId(), "70000", "completed");

        MvcResult r = list(fx.login(ana.account()), "");

        assertThat(status(r)).isEqualTo(200);
        String json = body(r);
        assertThat(json).contains("\"total\":2").contains("\"status\":\"voided\"").doesNotContain("70000");
        assertThat(json).contains("\"completedTotal\":50000");
    }

    // El Administrador filtra por estado y rango de fechas: solo salen los que cumplen ambos.
    @Test
    void elAdministradorFiltraPorEstadoYRangoDeFechas() throws Exception {
        Org org = fx.orgWithAdmin();
        Client ana = client(org.id());
        long viejo = payment(org.id(), ana.memberId(), org.adminMemberId(), "10000", "completed");
        jdbc.update("ALTER TABLE payments DISABLE TRIGGER trg_payments_immutable");
        jdbc.update("UPDATE payments SET paid_at = TIMESTAMPTZ '2026-01-10 12:00:00-05' WHERE id = ?", viejo);
        jdbc.update("ALTER TABLE payments ENABLE TRIGGER trg_payments_immutable");
        long reciente = payment(org.id(), ana.memberId(), org.adminMemberId(), "20000", "completed");
        payment(org.id(), ana.memberId(), org.adminMemberId(), "30000", "pending");
        Cookie admin = fx.login(org.admin());

        String enero = body(list(admin, "?status=completed&from=2026-01-01&to=2026-01-31"));
        assertThat(enero).contains("\"id\":" + viejo).doesNotContain("\"id\":" + reciente).contains("\"total\":1");

        String pendientes = body(list(admin, "?status=pending"));
        assertThat(pendientes).contains("\"total\":1").contains("30000");

        assertThat(status(list(admin, "?status=otro"))).isEqualTo(400);
        assertThat(status(list(admin, "?from=ayer"))).isEqualTo(400);
        assertThat(status(list(admin, "?size=1000"))).isEqualTo(400);
    }

    // RN-20: un usuario de otro gimnasio no ve estos pagos.
    @Test
    @ReglaDeNegocio("RN-06")
    void unUsuarioDeOtroGimnasioNoVeLosPagos() throws Exception {
        Org org = fx.orgWithAdmin();
        Org otro = fx.orgWithAdmin();
        Client ana = client(org.id());
        long id = payment(org.id(), ana.memberId(), org.adminMemberId(), "50000", "pending");

        String lista = body(list(fx.login(otro.admin()), ""));
        assertThat(lista).contains("\"total\":0");
        assertThat(status(voidIt(fx.login(otro.admin()), id, "{\"reason\":\"x\"}"))).isEqualTo(404);
        assertThat(status(complete(fx.login(otro.admin()), id))).isEqualTo(404);
        assertThat(row(id)).containsEntry("status", "pending");
    }

    // ---- US-27: anular -------------------------------------------------------------------

    // Anular exige motivo; el pago queda anulado, no se borra y se audita.
    @Test
    void anularUnPagoPendienteExigeMotivoYQuedaAuditado() throws Exception {
        Org org = fx.orgWithAdmin();
        Client ana = client(org.id());
        Account staff = fx.actor(org.id(), "staff");
        long id = payment(org.id(), ana.memberId(), org.adminMemberId(), "50000", "pending");
        Cookie session = fx.login(staff);

        MvcResult sin = voidIt(session, id, "{}");
        assertThat(status(sin)).isEqualTo(400);
        assertThat(body(sin)).contains("\"field\":\"reason\"");
        assertThat(row(id)).containsEntry("status", "pending");

        MvcResult ok = voidIt(session, id, "{\"reason\":\"Registrado por error\"}");
        assertThat(status(ok)).isEqualTo(200);
        assertThat(row(id)).containsEntry("status", "voided").containsEntry("void_reason", "Registrado por error");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = 'PAYMENT_VOIDED'"
            + " AND entity_id = ?", Integer.class, id)).isEqualTo(1);

        assertThat(status(voidIt(session, id, "{\"reason\":\"otra vez\"}"))).isEqualTo(409);
    }

    // El trigger RN-40 hace finales a los pagos completados: no se anulan ni se completan de nuevo.
    @Test
    void unPagoCompletadoNoSeAnulaNiSeVuelveACompletar() throws Exception {
        Org org = fx.orgWithAdmin();
        Client ana = client(org.id());
        long id = payment(org.id(), ana.memberId(), org.adminMemberId(), "50000", "completed");
        Cookie admin = fx.login(org.admin());

        assertThat(status(voidIt(admin, id, "{\"reason\":\"x\"}"))).isEqualTo(409);
        assertThat(status(complete(admin, id))).isEqualTo(409);
        assertThat(row(id)).containsEntry("status", "completed");
    }

    // ---- US-50: completar ----------------------------------------------------------------

    // Staff y Administrador completan un pendiente: se fijan fecha y autor, y se audita.
    @Test
    void completarUnPagoPendienteFijaFechaAutorYAuditoria() throws Exception {
        Org org = fx.orgWithAdmin();
        Client ana = client(org.id());
        long id = payment(org.id(), ana.memberId(), org.adminMemberId(), "50000", "pending");

        MvcResult r = complete(fx.login(org.admin()), id);

        assertThat(status(r)).isEqualTo(200);
        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "completed").containsEntry("completed_by_member_id", org.adminMemberId());
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = 'PENDING_PAYMENT_COMPLETED'"
            + " AND entity_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(status(complete(fx.login(org.admin()), id))).isEqualTo(409);
    }

    // Cliente y Entrenador reciben 403 al completar o anular.
    @Test
    void clienteYEntrenadorNoCompletanNiAnulan() throws Exception {
        Org org = fx.orgWithAdmin();
        Client ana = client(org.id());
        long id = payment(org.id(), ana.memberId(), org.adminMemberId(), "50000", "pending");

        for (Cookie s : new Cookie[] {fx.login(ana.account()), fx.login(fx.actor(org.id(), "trainer"))}) {
            assertThat(status(complete(s, id))).isEqualTo(403);
            assertThat(status(voidIt(s, id, "{\"reason\":\"x\"}"))).isEqualTo(403);
        }
        assertThat(row(id)).containsEntry("status", "pending");
    }
}

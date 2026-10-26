package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nexfit.ReglaDeNegocio;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** US-02 (NEX-22): cambio de contraseña temporal y voluntario (RF-102, RN-38, RNF-06, RNF-07). */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthTests.Probe.class)
class PasswordChangeTests {

    private static final String TEMP = "Temporal-1234";
    private static final String OWN = "Clave-Propia-123";
    private static final String NEW = "Otra-Clave-Nueva-456";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Usuario con la contraseña dada; {@code tempExpiresSql} es una expresión SQL (p. ej. "now() + interval '72 hours'") o "NULL". */
    private long newUser(String email, String password, boolean mustChange, String tempExpiresSql) {
        int n = 2000 + SEQ.incrementAndGet();
        return jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, must_change_password, temp_password_expires_at,
                               is_platform_admin, is_active)
            VALUES ('CC', ?, 'Ana', 'Pérez', DATE '1990-01-01', ?, ?, ?, %s, false, true) RETURNING id
            """.formatted(tempExpiresSql), Long.class, "P" + n, email, encoder.encode(password), mustChange);
    }

    private long newTempUser(String email) {
        return newUser(email, TEMP, true, "now() + interval '72 hours'");
    }

    private long newOwnUser(String email) {
        return newUser(email, OWN, false, "NULL");
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    private Cookie loginOk(String email, String password) throws Exception {
        MvcResult r = login(email, password);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getCookie("NEXFIT_SESSION");
    }

    private MvcResult changePassword(Cookie cookie, String current, String next) throws Exception {
        return mvc.perform(post("/auth/password").with(csrf()).cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next))).andReturn();
    }

    private int probe(Cookie cookie) throws Exception {
        return mvc.perform(get("/test/authorities").cookie(cookie)).andReturn().getResponse().getStatus();
    }

    private String hash(long userId) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
    }

    // Criterio 1
    @Test
    @ReglaDeNegocio("RN-38")
    void conContrasenaTemporalSoloSePuedeCambiarlaOCerrarSesion() throws Exception {
        newTempUser("temp1@nexfit.test");
        MvcResult r = login("temp1@nexfit.test", TEMP);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"mustChangePassword\":true");
        Cookie cookie = r.getResponse().getCookie("NEXFIT_SESSION");

        MvcResult bloqueado = mvc.perform(get("/test/authorities").cookie(cookie)).andReturn();
        assertThat(bloqueado.getResponse().getStatus()).isEqualTo(403);
        assertThat(bloqueado.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .contains("PASSWORD_CHANGE_REQUIRED");

        // Cambiar la contraseña y cerrar sesión sí están permitidos.
        assertThat(changePassword(cookie, TEMP, "x").getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/auth/logout").with(csrf()).cookie(cookie)).andReturn().getResponse().getStatus())
            .isEqualTo(204);
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void unUsuarioConContrasenaPropiaNoQuedaBloqueadoYElLoginIndicaQueNoHayCambioPendiente() throws Exception {
        newOwnUser("propia@nexfit.test");
        MvcResult r = login("propia@nexfit.test", OWN);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"mustChangePassword\":false");
        //assertThat(probe(r.getResponse().getCookie("NEXFIT_SESSION"))).isEqualTo(200);
         // NEX-24: sin establecimiento activo no se puede operar.
        assertThat(probe(r.getResponse().getCookie("NEXFIT_SESSION"))).isEqualTo(403);
    }

    // Criterio 2
    @Test
    @ReglaDeNegocio("RN-38")
    void unaContrasenaNuevaDeMenosDe10CaracteresSeRechazaIndicandoLaPolitica() throws Exception {
        long id = newTempUser("corta@nexfit.test");
        Cookie cookie = loginOk("corta@nexfit.test", TEMP);
        String antes = hash(id);

        MvcResult r = changePassword(cookie, TEMP, "Corta-123");   // 9 caracteres
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("10 caracteres");
        assertThat(hash(id)).isEqualTo(antes);
        assertThat(probe(cookie)).isEqualTo(403);   // sigue pendiente
        // Diez caracteres justos sí alcanzan.
        assertThat(changePassword(cookie, TEMP, "Diez-Chars").getResponse().getStatus()).isEqualTo(204);
    }

    // Criterio 3
    @Test
    @ReglaDeNegocio("RN-38")
    void unaContrasenaValidaSeGuardaComoHashLimpiaLaMarcaYDejaContinuar() throws Exception {
        long id = newTempUser("valida@nexfit.test");
        Cookie cookie = loginOk("valida@nexfit.test", TEMP);

        assertThat(changePassword(cookie, TEMP, NEW).getResponse().getStatus()).isEqualTo(204);

        assertThat(hash(id)).startsWith("{bcrypt}").doesNotContain(NEW);
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class, id))
            .isFalse();
        assertThat(jdbc.queryForObject("SELECT temp_password_expires_at FROM users WHERE id = ?",
            java.sql.Timestamp.class, id)).isNull();
        assertThat(probe(cookie)).isEqualTo(403);   // la misma sesión sigue viva: autenticada, falta elegir establecimiento
        assertThat(login("valida@nexfit.test", TEMP).getResponse().getStatus()).isEqualTo(401);
        assertThat(login("valida@nexfit.test", NEW).getResponse().getStatus()).isEqualTo(200);
    }

    // Criterio 4
    @Test
    @ReglaDeNegocio("RN-38")
    void elCambioVoluntarioExigeLaPoliticaYLaAnteriorDejaDeFuncionar() throws Exception {
        long id = newOwnUser("voluntario@nexfit.test");
        Cookie cookie = loginOk("voluntario@nexfit.test", OWN);

        assertThat(changePassword(cookie, OWN, "corta").getResponse().getStatus()).isEqualTo(400);
        assertThat(changePassword(cookie, OWN, NEW).getResponse().getStatus()).isEqualTo(204);

        assertThat(login("voluntario@nexfit.test", OWN).getResponse().getStatus()).isEqualTo(401);
        assertThat(login("voluntario@nexfit.test", NEW).getResponse().getStatus()).isEqualTo(200);
        assertThat(hash(id)).startsWith("{bcrypt}");
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void laContrasenaNuevaDebeSerDistintaDeLaActual() throws Exception {
        long id = newOwnUser("igual@nexfit.test");
        Cookie cookie = loginOk("igual@nexfit.test", OWN);
        String antes = hash(id);

        assertThat(changePassword(cookie, OWN, OWN).getResponse().getStatus()).isEqualTo(400);
        assertThat(hash(id)).isEqualTo(antes);
    }

    // Criterio 5
    @Test
    @ReglaDeNegocio("RN-38")
    void unaTemporalVencidaNoSirveYNoCuentaComoIntentoFallido() throws Exception {
        long id = newUser("vencida@nexfit.test", TEMP, true, "now() - interval '1 hour'");

        MvcResult r = login("vencida@nexfit.test", TEMP);

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .isEqualTo("{\"message\":\"Credenciales inválidas\"}");
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, id))
            .isZero();
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void siLaTemporalVenceConLaSesionAbiertaLaSiguienteSolicitudDa401() throws Exception {
        long id = newTempUser("vence@nexfit.test");
        Cookie cookie = loginOk("vence@nexfit.test", TEMP);
        jdbc.update("UPDATE users SET temp_password_expires_at = now() - interval '1 second' WHERE id = ?", id);

        assertThat(probe(cookie)).isEqualTo(401);
        assertThat(changePassword(cookie, TEMP, NEW).getResponse().getStatus()).isEqualTo(401);
    }

    // Criterio 6
    @Test
    @ReglaDeNegocio("RN-38")
    void elCambioVoluntarioExigeLaContrasenaActual() throws Exception {
        long id = newOwnUser("actual@nexfit.test");
        Cookie cookie = loginOk("actual@nexfit.test", OWN);
        String antes = hash(id);

        MvcResult r = changePassword(cookie, "no-es-la-actual", NEW);

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("actual");
        assertThat(hash(id)).isEqualTo(antes);
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void trasCambiarLaContrasenaLasDemasSesionesQuedanRevocadasYLaActualSigue() throws Exception {
        newOwnUser("sesiones@nexfit.test");
        Cookie actual = loginOk("sesiones@nexfit.test", OWN);
        Cookie otraA = loginOk("sesiones@nexfit.test", OWN);
        Cookie otraB = loginOk("sesiones@nexfit.test", OWN);
        assertThat(probe(otraA)).isEqualTo(403);
        assertThat(probe(otraB)).isEqualTo(403);

        assertThat(changePassword(actual, OWN, NEW).getResponse().getStatus()).isEqualTo(204);

        assertThat(probe(actual)).isEqualTo(403);
        assertThat(probe(otraA)).isEqualTo(401);
        assertThat(probe(otraB)).isEqualTo(401);
    }

    @Test
    @ReglaDeNegocio("RN-38")
    void cambiarLaContrasenaNoRevocaLasSesionesDeOtroUsuario() throws Exception {
        newOwnUser("yo@nexfit.test");
        newOwnUser("otro@nexfit.test");
        Cookie mia = loginOk("yo@nexfit.test", OWN);
        Cookie ajena = loginOk("otro@nexfit.test", OWN);

        assertThat(changePassword(mia, OWN, NEW).getResponse().getStatus()).isEqualTo(204);

        assertThat(probe(ajena)).isEqualTo(403);

    }

    @Test
    @ReglaDeNegocio("RN-38")
    void sinSesionNoSePuedeCambiarLaContrasena() throws Exception {
        MvcResult r = mvc.perform(post("/auth/password").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }
}

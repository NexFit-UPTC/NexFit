package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexfit.shared.AnyAuthenticated;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthTests.Probe.class)
class AuthTests {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String GENERIC = "{\"message\":\"Credenciales inválidas\"}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    /** Endpoint de prueba: devuelve las autoridades que la solicitud actual tiene en el servidor. */
    @TestConfiguration
    static class Probe {
        @RestController
        static class ProbeController {
            @AnyAuthenticated
            @GetMapping("/test/authorities")
            List<String> authorities(Authentication auth) {
                return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
            }
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long newUser(String email, boolean active, String lockedUntilSql) {
        int n = SEQ.incrementAndGet();
        return jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active, locked_until)
            VALUES ('CC', ?, 'Ana', 'Pérez', DATE '1990-01-01', ?, ?, false, ?, %s) RETURNING id
            """.formatted(lockedUntilSql), Long.class, "D" + n, email, encoder.encode(PASSWORD), active);
    }

    private long newUser(String email) {
        return newUser(email, true, "NULL");
    }

    private long newOrg() {
        return jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES ('Gym', 'g@nexfit.test', 'America/Bogota', true) RETURNING id
            """, Long.class);
    }

    /** Afilia al usuario con un rol; devuelve el id de organization_members. */
    private long affiliate(long userId, long orgId, String role) {
        long memberId = jdbc.queryForObject("""
            INSERT INTO organization_members (organization_id, user_id, is_active)
            VALUES (?, ?, true) RETURNING id
            """, Long.class, orgId, userId);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST(? AS member_role), ?)",
            memberId, role, orgId);
        return memberId;
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    private Cookie sessionCookie(MvcResult login) {
        return login.getResponse().getCookie("NEXFIT_SESSION");
    }

    private int events(String type, Long actor) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = ? AND actor_user_id IS NOT DISTINCT FROM ?",
            Integer.class, type, actor);
    }

    // Criterio 1
    @Test
    void credencialesValidasAccedenYSeAuditaElInicio() throws Exception {
        long id = newUser("ok@nexfit.test");
        MvcResult r = login("ok@nexfit.test", PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(sessionCookie(r)).isNotNull();
        assertThat(events("LOGIN", id)).isEqualTo(1);
        // NEX-24: sin establecimiento activo no se puede operar.
        mvc.perform(get("/test/authorities").cookie(sessionCookie(r))).andExpect(status().isForbidden());

    }

    // Criterio 2
    @Test
    void correoInexistenteOContrasenaIncorrectaDan401IdenticoYSeAudita() throws Exception {
        long id = newUser("existe@nexfit.test");
        MvcResult noEmail = login("noexiste@nexfit.test", PASSWORD);
        MvcResult badPass = login("existe@nexfit.test", "incorrecta");
        assertThat(noEmail.getResponse().getStatus()).isEqualTo(401);
        assertThat(badPass.getResponse().getStatus()).isEqualTo(401);
        assertThat(noEmail.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(GENERIC);
        assertThat(badPass.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(GENERIC);
        assertThat(events("LOGIN_FAILED", id)).isEqualTo(1);
        assertThat(events("LOGIN_FAILED", null)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void usuarioInactivoOBloqueadoDa401GenericoSinContarIntentos() throws Exception {
        long inactive = newUser("inactivo@nexfit.test", false, "NULL");
        long locked = newUser("bloqueado@nexfit.test", true, "now() + interval '1 hour'");
        for (String email : List.of("inactivo@nexfit.test", "bloqueado@nexfit.test")) {
            MvcResult r = login(email, PASSWORD);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(GENERIC);
        }
        assertThat(events("LOGIN_FAILED", inactive)).isEqualTo(1);
        assertThat(events("LOGIN_FAILED", locked)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(failed_login_count) FROM users WHERE id IN (?, ?)",
            Long.class, inactive, locked)).isZero();
    }

    // Criterio 3 (RN-26)
    @Test
    void elCorreoNoDistingueMayusculas() throws Exception {
        newUser("Mixto@NexFit.test");
        assertThat(login("mixto@nexfit.test", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(login("MIXTO@NEXFIT.TEST", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> newUser("MIXTO@nexfit.test"))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // Criterio 4
    @Test
    void logoutInvalidaLaSesionEnBdYSeAudita() throws Exception {
        long id = newUser("salir@nexfit.test");
        Cookie cookie = sessionCookie(login("salir@nexfit.test", PASSWORD));
        int before = jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class);
        mvc.perform(post("/auth/logout").with(csrf()).cookie(cookie)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isEqualTo(before - 1);
        mvc.perform(get("/test/authorities").cookie(cookie)).andExpect(status().isUnauthorized());
        assertThat(events("LOGOUT", id)).isEqualTo(1);
    }

    // Criterio 5 (RNF-07): se vence la sesión en BD sin esperar 30 minutos
    @Test
    void trasInactividadLaSesionDa401() throws Exception {
        newUser("inactiva@nexfit.test");
        /*Cookie cookie = sessionCookie(login("inactiva@nexfit.test", PASSWORD));
        mvc.perform(get("/test/authorities").cookie(cookie)).andExpect(status().isOk());*/
        Cookie cookie = sessionCookie(login("inactiva@nexfit.test", PASSWORD));
        // NEX-24: sin establecimiento activo no se puede operar.
        mvc.perform(get("/test/authorities").cookie(cookie)).andExpect(status().isForbidden());
        jdbc.update("UPDATE spring_session SET last_access_time = last_access_time - 1860000, "
            + "expiry_time = expiry_time - 1860000");
        mvc.perform(get("/test/authorities").cookie(cookie)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT max_inactive_interval FROM spring_session LIMIT 1", Integer.class))
            .isEqualTo(1800);
    }

    // Criterio 6
    @Test
    void soloSeGuardaElHashYLaCookieEsSegura() throws Exception {
        long id = newUser("hash@nexfit.test");
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        MvcResult r = login("hash@nexfit.test", PASSWORD);
        String setCookie = r.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("NEXFIT_SESSION=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
    }

    // Criterio 7: ver CsrfCookieTests.laSesionGuardaSoloLaIdentidadNoLosRoles (necesita el flujo CSRF real, sin csrf()).

    // Criterio 8
    @Test
    void loCambiadoEnBdAplicaEnLaSiguienteSolicitudConLaMismaCookie() throws Exception {
        long user = newUser("cambio@nexfit.test");
        long org = newOrg();
        long member = affiliate(user, org, "staff");
        affiliate(newUser("admin-cambio@nexfit.test"), org, "admin");
        Cookie cookie = sessionCookie(login("cambio@nexfit.test", PASSWORD));
        String auths = mvc.perform(get("/test/authorities").cookie(cookie)).andReturn().getResponse().getContentAsString();
        assertThat(auths).isEqualTo("[\"staff:" + org + "\"]");

        jdbc.update("UPDATE member_roles SET role = 'trainer' WHERE member_id = ?", member);
        auths = mvc.perform(get("/test/authorities").cookie(cookie)).andReturn().getResponse().getContentAsString();
        assertThat(auths).isEqualTo("[\"trainer:" + org + "\"]");

        /*jdbc.update("UPDATE organization_members SET is_active = false WHERE id = ?", member);
        auths = mvc.perform(get("/test/authorities").cookie(cookie)).andReturn().getResponse().getContentAsString();
        assertThat(auths).isEqualTo("[]");

        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", user); */
        jdbc.update("UPDATE organization_members SET is_active = false WHERE id = ?", member);
        // NEX-24: sin afiliación vigente hay que volver a elegir establecimiento; la sesión sigue viva.
        mvc.perform(get("/test/authorities").cookie(cookie)).andExpect(status().isForbidden());

        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", user);
        mvc.perform(get("/test/authorities").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void gimnasioDesactivadoQuitaLasAutoridadesPeroNoLaSesion() throws Exception {
        long user = newUser("gym@nexfit.test");
        long org = newOrg();
        affiliate(user, org, "staff");
        Cookie cookie = sessionCookie(login("gym@nexfit.test", PASSWORD));
        /*jdbc.update("UPDATE organizations SET is_active = false WHERE id = ?", org);
        String auths = mvc.perform(get("/test/authorities").cookie(cookie)).andReturn().getResponse().getContentAsString();
        assertThat(auths).isEqualTo("[]");*/
        jdbc.update("UPDATE organizations SET is_active = false WHERE id = ?", org);
        // NEX-24: el gimnasio desactivado invalida la selección; la sesión sigue viva.
        mvc.perform(get("/test/authorities").cookie(cookie)).andExpect(status().isForbidden());
    
    }
}
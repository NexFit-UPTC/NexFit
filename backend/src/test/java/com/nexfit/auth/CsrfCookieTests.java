package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexfit.shared.AnyAuthenticated;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Flujo CSRF completo como lo hace Angular, sin el post-procesador csrf() de MockMvc (NEX-80). */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(CsrfCookieTests.Probe.class)
class CsrfCookieTests {

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

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String BODY = "{\"email\":\"csrf@nexfit.test\",\"password\":\"%s\"}".formatted(PASSWORD);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private void newUser() {
        jdbc.update("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', 'CSRF1', 'Ana', 'Pérez', DATE '1990-01-01', 'csrf@nexfit.test', ?, false, true)
            ON CONFLICT DO NOTHING
            """, encoder.encode(PASSWORD));
    }

    @Test
    void unaRutaPublicaEmiteLaCookieXsrfTokenLegibleDesdeJs() throws Exception {
        var response = mvc.perform(get("/actuator/health")).andReturn().getResponse();
        assertThat(response.getCookie("XSRF-TOKEN")).isNotNull();
        String header = response.getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(header).doesNotContain("HttpOnly");
    }

    @Test
    void conElValorDeLaCookieEnLaCabeceraElLoginResponde200() throws Exception {
        newUser();
        Cookie xsrf = mvc.perform(get("/actuator/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        mvc.perform(post("/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk());
    }

    // Criterio 7 (movida desde AuthTests: csrf() guarda su token en la sesión y falsearía esta aserción)
    @Test
    void laSesionGuardaSoloLaIdentidadNoLosRoles() throws Exception {
        long id = jdbc.queryForObject("""
            INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                               password_hash, is_platform_admin, is_active)
            VALUES ('CC', 'IDENT1', 'Ana', 'Pérez', DATE '1990-01-01', 'ident@nexfit.test', ?, false, true)
            RETURNING id
            """, Long.class, encoder.encode(PASSWORD));
        long orgId = jdbc.queryForObject("""
            INSERT INTO organizations (name, contact_email, timezone, is_active)
            VALUES ('Gym', 'g@nexfit.test', 'America/Bogota', true) RETURNING id
            """, Long.class);
        long memberId = jdbc.queryForObject("""
            INSERT INTO organization_members (organization_id, user_id, is_active)
            VALUES (?, ?, true) RETURNING id
            """, Long.class, orgId, id);
        jdbc.update("INSERT INTO member_roles (member_id, role, organization_id) VALUES (?, CAST(? AS member_role), ?)",
            memberId, "admin", orgId);

        // Flujo CSRF real (cookie + cabecera), sin csrf().
        Cookie xsrf = mvc.perform(get("/actuator/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        Cookie cookie = mvc.perform(post("/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ident@nexfit.test\",\"password\":\"%s\"}".formatted(PASSWORD)))
            .andReturn().getResponse().getCookie("NEXFIT_SESSION");
        mvc.perform(get("/test/authorities").cookie(cookie)).andExpect(status().isOk());
        List<Map<String, Object>> attrs = jdbc.queryForList(
            "SELECT a.attribute_name, a.attribute_bytes FROM spring_session_attributes a "
                + "JOIN spring_session s ON s.primary_id = a.session_primary_id WHERE s.session_id = ?",
            new String(java.util.Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8));
        // La sesión guarda la identidad y el índice por usuario de Spring Session (para poder revocar sesiones, US-02).
        /*assertThat(attrs).extracting(a -> a.get("attribute_name")).containsExactlyInAnyOrder("NEXFIT_USER_ID",
            org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);*/
            assertThat(attrs).extracting(a -> a.get("attribute_name")).containsExactlyInAnyOrder("NEXFIT_USER_ID",
            "NEXFIT_ORG_ID",
            org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME); 
        Map<String, Object> identity = attrs.stream()
            .filter(a -> "NEXFIT_USER_ID".equals(a.get("attribute_name"))).findFirst().orElseThrow();
        String bytes = new String((byte[]) identity.get("attribute_bytes"), StandardCharsets.ISO_8859_1);
        assertThat(bytes).doesNotContain("admin").doesNotContain("ROLE").doesNotContain("SecurityContext");
    }

    @Test
    void sinLaCabeceraXXsrfTokenElLoginResponde403() throws Exception {
        newUser();
        Cookie xsrf = mvc.perform(get("/actuator/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        mvc.perform(post("/auth/login").cookie(xsrf).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isForbidden());
    }
}

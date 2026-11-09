package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Entradas extrañas contra las rutas públicas: contraseñas enormes, JSON roto, método o tipo de contenido
 * equivocados y registros en ráfaga. Nada de esto debe terminar en un 500 y todo responde con {@code {code, message}}.
 */
@Testcontainers
@SpringBootTest(properties = "nexfit.auth.register-max-per-hour=8")
@AutoConfigureMockMvc
class ApiHardeningTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String content(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MvcResult post(String path, String contentType, String body) throws Exception {
        return mvc.perform(post(path).with(csrf()).contentType(contentType).content(body)).andReturn();
    }

    private String registerBody(String password) {
        int n = SEQ.incrementAndGet();
        return """
                {"documentType":"CC","documentNumber":"H%d","firstName":"Ana","lastName":"Pérez",
                 "birthDate":"1990-01-01","email":"hard%d@nexfit.test","password":"%s"}
                """.formatted(n, n, password);
    }

    @Test
    void unaContrasenaEnormeSeRechazaConUn400EnVezDeUn500() throws Exception {
        MvcResult tooLong = post("/auth/register", "application/json", registerBody("a".repeat(65)));
        MvcResult hugeBytes = post("/auth/register", "application/json", registerBody("ñ".repeat(40)));
        MvcResult atLimit = post("/auth/register", "application/json", registerBody("a".repeat(64)));

        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(tooLong)).contains("\"field\":\"password\"");
        assertThat(hugeBytes.getResponse().getStatus()).as("40 caracteres de 2 bytes pasan de 72 bytes").isEqualTo(400);
        assertThat(atLimit.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void loginConUnaContrasenaGiganteDa401YNoSeCae() throws Exception {
        MvcResult r = post("/auth/login", "application/json",
            "{\"email\":\"nadie@nexfit.test\",\"password\":\"" + "x".repeat(100_000) + "\"}");

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void unJsonRotoDa400ConElFormatoDeLaApi() throws Exception {
        MvcResult broken = post("/auth/login", "application/json", "{\"email\": ");
        MvcResult notAnObject = post("/auth/login", "application/json", "[1,2,3]");
        MvcResult wrongType = post("/auth/login", "application/json", "{\"email\": 5, \"password\": {}}");

        for (MvcResult r : new MvcResult[] {broken, notAnObject, wrongType}) {
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(content(r)).contains("\"code\":\"BAD_REQUEST\"").doesNotContain("Exception");
        }
    }

    @Test
    void metodoOTipoDeContenidoEquivocadosDan405Y415() throws Exception {
        MvcResult method = mvc.perform(get("/auth/register")).andReturn();
        MvcResult media = post("/auth/login", "text/plain", "hola");

        assertThat(method.getResponse().getStatus()).isEqualTo(405);
        assertThat(content(method)).contains("METHOD_NOT_ALLOWED");
        assertThat(method.getResponse().getHeader("Allow")).contains("POST");
        assertThat(media.getResponse().getStatus()).isEqualTo(415);
        assertThat(content(media)).contains("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unaRafagaDeRegistrosDesdeLaMismaConexionTerminaEn429() throws Exception {
        int tooMany = 0;
        for (int i = 0; i < 20; i++) {
            MvcResult r = mvc.perform(post("/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("Clave-Segura-123"))).andReturn();
            if (r.getResponse().getStatus() == 429) {
                tooMany++;
                assertThat(content(r)).contains("TOO_MANY_REQUESTS");
                assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("3600");
            }
        }

        assertThat(tooMany).as("con tope de 8 por hora, la ráfaga de 20 debe cortarse").isGreaterThan(0);
    }
}

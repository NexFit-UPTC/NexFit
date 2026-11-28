package com.nexfit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Auto-registro público (BE-11, NEX-102): POST /api/auth/register sin sesión.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PublicRegistrationTests {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String SHORT_PASSWORD = "corta123"; // 8 caracteres
    // NEX-149 (decisión del 28/11): el 409 ya no es genérico; dice qué dato se repite.
    private static final String DOCUMENT_TAKEN_BODY = "{\"code\":\"DOCUMENT_TAKEN\",\"field\":\"documentNumber\","
            + "\"message\":\"Ya hay una cuenta con este número de documento\"}";
    private static final String EMAIL_TAKEN_BODY = "{\"code\":\"EMAIL_TAKEN\",\"field\":\"email\","
            + "\"message\":\"Ya hay una cuenta con este correo\"}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String body(String documentNumber, String email, String password) {
        return """
                {"documentType":"CC","documentNumber":"%s","firstName":"Ana","lastName":"Pérez",
                 "birthDate":"1990-01-01","email":"%s","password":"%s"}
                """.formatted(documentNumber, email, password);
    }

    private MvcResult register(String json) throws Exception {
        return mvc.perform(post("/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private static String content(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private int events(String type, Long actor) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = ? AND actor_user_id IS NOT DISTINCT FROM ?",
                Integer.class, type, actor);
    }

    private long userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE lower(email) = lower(?)", Long.class, email);
    }

    @Test
    void registroValidoCreaSoloLaCuentaSinRolesYSeAudita() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "registro" + n + "@nexfit.test";
        MvcResult r = register(body("R" + n, email, PASSWORD));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(content(r)).contains("\"userId\"");
        assertThat(r.getResponse().getCookie("NEXFIT_SESSION")).isNull();
        long id = userIdByEmail(email);
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT is_platform_admin FROM users WHERE id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, id)).isTrue();
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM member_roles mr JOIN organization_members om ON om.id = mr.member_id WHERE om.user_id = ?",
                Integer.class, id)).isZero();
        assertThat(events("ACCOUNT_SELF_REGISTERED", id)).isEqualTo(1);
        // Sin afiliaciones, el login posterior no da establecimientos para operar.
        MvcResult login = mvc.perform(post("/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(content(login)).contains("\"establishments\":[]");
    }

    @Test
    void correoDuplicadoDa409EmailTakenEnElCampoCorreo() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "duplicado" + n + "@nexfit.test";
        assertThat(register(body("D" + n, email, PASSWORD)).getResponse().getStatus()).isEqualTo(201);
        // Mismo correo con otras mayúsculas: 409 que señala el correo.
        MvcResult r = register(body("D" + n + "X", email.toUpperCase(), PASSWORD));
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(EMAIL_TAKEN_BODY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?)",
                Integer.class, email)).isEqualTo(1);
    }

    @Test
    void documentoDuplicadoDa409DocumentTakenEnElCampoDocumento() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "doc" + n + "@nexfit.test";
        String doc = "DOC" + n;
        assertThat(register(body(doc, email, PASSWORD)).getResponse().getStatus()).isEqualTo(201);
        // Mismo documento con otro correo: 409 que señala el documento.
        MvcResult r = register(body(doc, "otro" + n + "@nexfit.test", PASSWORD));
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(DOCUMENT_TAKEN_BODY);
    }

    @Test
    void elMismoNumeroConOtroTipoDeDocumentoDa409YNoUn500() throws Exception {
        int n = SEQ.incrementAndGet();
        String doc = "TIPO" + n;
        assertThat(register(body(doc, "tipo" + n + "@nexfit.test", PASSWORD)).getResponse().getStatus())
                .isEqualTo(201);

        // La unicidad de la BD es solo por número: CE con el número de una CC también choca.
        MvcResult r = register(body(doc, "tipo-otro" + n + "@nexfit.test", PASSWORD)
                .replace("\"documentType\":\"CC\"", "\"documentType\":\"CE\""));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(DOCUMENT_TAKEN_BODY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE document_number = ?", Integer.class, doc))
                .isEqualTo(1);
    }

    @Test
    void siSeRepitenElDocumentoYElCorreoSeInformaElDocumento() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "ambos" + n + "@nexfit.test";
        String doc = "AMBOS" + n;
        assertThat(register(body(doc, email, PASSWORD)).getResponse().getStatus()).isEqualTo(201);

        MvcResult r = register(body(doc, email, PASSWORD));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(content(r)).isEqualTo(DOCUMENT_TAKEN_BODY);
    }

    @Test
    void despuesDeUn409SeCorrigeElDatoYElMismoRegistroSeCrea() throws Exception {
        int n = SEQ.incrementAndGet();
        String doc = "CORR" + n;
        assertThat(register(body(doc, "corr" + n + "@nexfit.test", PASSWORD)).getResponse().getStatus())
                .isEqualTo(201);
        String email = "corr-nuevo" + n + "@nexfit.test";
        assertThat(register(body(doc, email, PASSWORD)).getResponse().getStatus()).isEqualTo(409);

        assertThat(register(body(doc + "B", email, PASSWORD)).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void cuerpoInvalidoDa400ConFieldYMessage() throws Exception {
        int n = SEQ.incrementAndGet();
        MvcResult badEmail = register(body("V" + n, "no-es-un-correo", PASSWORD));
        assertThat(badEmail.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(badEmail)).contains("\"code\":\"VALIDATION_ERROR\"").contains("\"field\":\"email\"");
        MvcResult shortPass = register(body("V" + n, "corto" + n + "@nexfit.test", SHORT_PASSWORD));
        assertThat(shortPass.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(shortPass)).contains("\"field\":\"password\"");
        MvcResult badType = register("""
                {"documentType":"XX","documentNumber":"V%s","firstName":"Ana","lastName":"Pérez",
                 "birthDate":"1990-01-01","email":"tipo%s@nexfit.test","password":"%s"}
                """.formatted(n, n, PASSWORD));
        assertThat(badType.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(badType)).contains("\"field\":\"documentType\"");
        MvcResult badBirth = register("""
                {"documentType":"CC","documentNumber":"V%s","firstName":"Ana","lastName":"Pérez",
                 "birthDate":"01-01-1990","email":"fecha%s@nexfit.test","password":"%s"}
                """.formatted(n, n, PASSWORD));
        assertThat(badBirth.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(badBirth)).contains("\"field\":\"birthDate\"");
        MvcResult noName = register("""
                {"documentType":"CC","documentNumber":"V%s","firstName":"","lastName":"Pérez",
                 "birthDate":"1990-01-01","email":"nombre%s@nexfit.test","password":"%s"}
                """.formatted(n, n, PASSWORD));
        assertThat(noName.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(noName)).contains("\"field\":\"firstName\"");
    }

    @Test
    void unaContrasenaDebilDe10CaracteresSeRechazaConMensajeClaro() throws Exception {
        int n = SEQ.incrementAndGet();
        // Solo dígitos (H-01: el caso que motivó la issue).
        MvcResult soloDigitos = register(body("W" + n, "debil1" + n + "@nexfit.test", "1234567890"));
        assertThat(soloDigitos.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(soloDigitos)).contains("\"code\":\"VALIDATION_ERROR\"")
                .contains("\"field\":\"password\"").contains("demasiado débil");
        // Secuencia evidente de letras.
        MvcResult secuencia = register(body("W" + n, "debil2" + n + "@nexfit.test", "abcdefghij"));
        assertThat(secuencia.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(secuencia)).contains("\"field\":\"password\"");
        // Un solo carácter repetido.
        MvcResult repetida = register(body("W" + n, "debil3" + n + "@nexfit.test", "aaaaaaaaaa"));
        assertThat(repetida.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(repetida)).contains("\"field\":\"password\"");
        // Contraseña común de la lista.
        MvcResult comun = register(body("W" + n, "debil4" + n + "@nexfit.test", "password123"));
        assertThat(comun.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(comun)).contains("\"field\":\"password\"");
        // Recorrido de teclado que no es secuencia alfanumérica: lo caza la lista.
        MvcResult teclado = register(body("W" + n, "debil5" + n + "@nexfit.test", "qwertyuiop"));
        assertThat(teclado.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(teclado)).contains("\"field\":\"password\"");
    }

    @Test
    void losBordesDeLongitudSiguenVigentesConLaReglaNueva() throws Exception {
        int n = SEQ.incrementAndGet();
        // Fuerte de 9 caracteres: sigue siendo corta.
        MvcResult nueve = register(body("B" + n, "nueve" + n + "@nexfit.test", "Clave-123"));
        assertThat(nueve.getResponse().getStatus()).isEqualTo(400);
        assertThat(content(nueve)).contains("\"field\":\"password\"");
        // Fuerte de 11 caracteres: se crea (sin regresión en el registro válido;
        // "Clave-Segura-123" ya lo cubre registroValidoCreaSoloLaCuentaSinRolesYSeAudita).
        MvcResult once = register(body("B" + n, "once" + n + "@nexfit.test", "Segura-1a2b"));
        assertThat(once.getResponse().getStatus()).isEqualTo(201);
        assertThat(content(once)).contains("\"userId\"");
    }
}

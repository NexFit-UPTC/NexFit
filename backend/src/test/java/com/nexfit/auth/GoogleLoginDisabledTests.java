package com.nexfit.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Sin GOOGLE_CLIENT_ID y GOOGLE_CLIENT_SECRET la cadena de OAuth2 no existe (NEX-147): la aplicación
 * arranca igual y las rutas de Google caen en la regla general (401 sin sesión). Contexto aparte para
 * que las propiedades de {@link GoogleLoginTests} no se filtren a estas pruebas.
 */
@Testcontainers
@SpringBootTest(properties = {"GOOGLE_CLIENT_ID=solo-el-id", "GOOGLE_CLIENT_SECRET="})
@AutoConfigureMockMvc
class GoogleLoginDisabledTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void sinElSecretoLaAutorizacionDeGoogleDa401() throws Exception {
        mvc.perform(get("/oauth2/authorization/google")).andExpect(status().isUnauthorized());
    }

    @Test
    void sinElSecretoElCallbackDeGoogleDa401() throws Exception {
        mvc.perform(get("/login/oauth2/code/google")).andExpect(status().isUnauthorized());
    }

    // NEX-148: el controlador del registro con Google existe siempre, pero sin Google nunca hay pendiente.
    @Test
    void sinElSecretoElGetDelRegistroConGoogleDa404() throws Exception {
        mvc.perform(get("/auth/google-registration")).andExpect(status().isNotFound());
    }

    @Test
    void sinElSecretoElPostDelRegistroConGoogleDa404() throws Exception {
        mvc.perform(post("/auth/google-registration").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentType\":\"CC\",\"documentNumber\":\"D1\",\"firstName\":\"Ana\","
                        + "\"lastName\":\"Pérez\",\"birthDate\":\"1990-01-01\",\"acceptPolicy\":true}"))
                .andExpect(status().isNotFound());
    }
}

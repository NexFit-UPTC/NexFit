package com.nexfit.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
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
}

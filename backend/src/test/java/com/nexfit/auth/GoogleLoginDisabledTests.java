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
 * Sin GOOGLE_CLIENT_ID la cadena OAuth2 no existe (NEX-147): la aplicación
 * arranca igual y los endpoints de Google quedan bajo la regla general
 * (401 sin sesión). Contexto aparte porque las propiedades de
 * {@link GoogleLoginTests} no deben filtrarse a estas pruebas.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class GoogleLoginDisabledTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void sinCredencialesLaAutorizacionDeGoogleDa401() throws Exception {
        mvc.perform(get("/oauth2/authorization/google")).andExpect(status().isUnauthorized());
    }

    @Test
    void sinCredencialesElCallbackDeGoogleDa401() throws Exception {
        mvc.perform(get("/login/oauth2/code/google")).andExpect(status().isUnauthorized());
    }
}

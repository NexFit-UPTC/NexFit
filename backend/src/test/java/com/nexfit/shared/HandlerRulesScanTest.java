package com.nexfit.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Matriz 3.7: todo endpoint de la aplicación declara su regla. Falla si alguno no tiene
 * {@link RequiresAccess} ni {@link AnyAuthenticated} (en el método o en la clase). Este contexto
 * no importa controllers de prueba: el sin anotación vive en {@link DefaultDenyTests}.
 */
@Testcontainers
@SpringBootTest
class HandlerRulesScanTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void todoHandlerDeComNexfitDeclaraSuRegla() {
        List<String> sinRegla = new ArrayList<>();
        int[] revisados = {0};
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (!AccessInterceptor.inApplication(handler)) {
                return;
            }
            revisados[0]++;
            if (AccessInterceptor.find(handler, RequiresAccess.class) == null
                && AccessInterceptor.find(handler, AnyAuthenticated.class) == null) {
                sinRegla.add(info + " -> " + handler.getShortLogMessage());
            }
        });
        assertThat(revisados[0]).as("handlers de com.nexfit revisados").isPositive();
        assertThat(sinRegla).as("endpoints sin @RequiresAccess ni @AnyAuthenticated").isEmpty();
    }
}

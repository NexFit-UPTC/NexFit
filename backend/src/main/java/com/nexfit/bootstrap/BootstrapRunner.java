package com.nexfit.bootstrap;

import java.io.Console;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Punto de entrada del procedimiento de inicialización (NEX-15). Solo existe con el perfil
 * {@code bootstrap}; con cualquier otro perfil (el de la API) este bean ni se crea. Ejecuta la
 * inicialización, imprime un resumen sin datos sensibles y termina el proceso.
 */
@Component
@Profile("bootstrap")
class BootstrapRunner implements ApplicationRunner {

    private final BootstrapService service;
    private final Environment env;
    private final ConfigurableApplicationContext context;

    BootstrapRunner(BootstrapService service, Environment env, ConfigurableApplicationContext context) {
        this.service = service;
        this.env = env;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code;
        try {
            MasterInputReader reader = new MasterInputReader(env, consolePrompt());
            BootstrapService.Result result = service.initialize(reader::read);
            System.out.println(summary(result));
            code = 0;
        } catch (RuntimeException e) {
            System.err.println("La inicialización falló y no se confirmó ningún cambio: " + e.getMessage());
            code = 1;
        }
        int exitCode = code;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    static String summary(BootstrapService.Result result) {
        return "Inicialización terminada. Master: %s · EPS nuevas: %d · ejercicios globales nuevos: %d."
            .formatted(result.masterCreated() ? "creado (debe cambiar la contraseña al primer ingreso)"
                                              : "ya existía, sin cambios",
                result.epsInserted(), result.exercisesInserted());
    }

    private static MasterInputReader.Prompt consolePrompt() {
        Console console = System.console();
        if (console == null || !console.isTerminal()) {
            return null;
        }
        return (label, secret) -> {
            if (secret) {
                char[] chars = console.readPassword("%s: ", label);
                return chars == null ? null : new String(chars);
            }
            return console.readLine("%s: ", label);
        };
    }
}

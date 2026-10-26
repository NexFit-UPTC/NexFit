package com.nexfit.bootstrap.seed;

import com.nexfit.bootstrap.seed.SeedService.Result;
import com.nexfit.bootstrap.seed.SeedService.Size;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Punto de entrada de la carga de datos de prueba (NEX-18). Solo existe con el perfil {@code seed}.
 *
 * <p>Variables de entorno:
 * <ul>
 *   <li>{@code NEXFIT_SEED_PASSWORD} (obligatoria, mínimo 12 caracteres): contraseña de todas las cuentas
 *       ficticias. No va en el repositorio.</li>
 *   <li>{@code NEXFIT_SEED_DATASET}: {@code functional} (por defecto), {@code reference} o {@code all}.</li>
 *   <li>{@code NEXFIT_SEED_ALLOW_REMOTE=true}: necesaria para cargar en una base que no sea local, para no
 *       llenar de datos falsos la base de producción por error.</li>
 * </ul>
 */
@Component
@Profile("seed")
class SeedRunner implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 12;

    private final SeedService service;
    private final Environment env;
    private final ConfigurableApplicationContext context;

    SeedRunner(SeedService service, Environment env, ConfigurableApplicationContext context) {
        this.service = service;
        this.env = env;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code;
        try {
            checkTarget();
            String password = password();
            List<String> lines = new ArrayList<>();
            for (String dataset : datasets()) {
                Result result = SeedService.FUNCTIONAL.equals(dataset)
                    ? service.loadFunctional(password)
                    : service.loadReference(password, Size.REFERENCE_DEFAULT);
                lines.add(summary(result));
            }
            lines.forEach(System.out::println);
            code = 0;
        } catch (RuntimeException e) {
            System.err.println("La carga de datos de prueba falló y no se confirmó ningún cambio: " + e.getMessage());
            code = 1;
        }
        int exitCode = code;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private void checkTarget() {
        String url = env.getProperty("spring.datasource.url", "");
        boolean allowRemote = "true".equalsIgnoreCase(env.getProperty("NEXFIT_SEED_ALLOW_REMOTE"));
        if (!isLocal(url) && !allowRemote) {
            throw new IllegalStateException("La base de datos no es local. Estos datos son ficticios: para cargarlos "
                + "en otra base define NEXFIT_SEED_ALLOW_REMOTE=true.");
        }
    }

    private String password() {
        String password = env.getProperty("NEXFIT_SEED_PASSWORD");
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Define NEXFIT_SEED_PASSWORD (mínimo " + MIN_PASSWORD_LENGTH
                + " caracteres).");
        }
        return password;
    }

    private List<String> datasets() {
        String value = env.getProperty("NEXFIT_SEED_DATASET", SeedService.FUNCTIONAL).trim().toLowerCase();
        return switch (value) {
            case SeedService.FUNCTIONAL -> List.of(SeedService.FUNCTIONAL);
            case SeedService.REFERENCE -> List.of(SeedService.REFERENCE);
            case "all" -> List.of(SeedService.FUNCTIONAL, SeedService.REFERENCE);
            default -> throw new IllegalArgumentException(
                "NEXFIT_SEED_DATASET debe ser functional, reference o all (recibido: " + value + ").");
        };
    }

    /** ¿La URL JDBC apunta a esta misma máquina? */
    static boolean isLocal(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return false;
        }
        try {
            String host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String summary(Result r) {
        if (r.skipped()) {
            return "Conjunto %s: ya estaba cargado, sin cambios.".formatted(r.dataset());
        }
        return "Conjunto %s cargado: %d gimnasios, %d clientes, %d ingresos."
            .formatted(r.dataset(), r.gyms(), r.clients(), r.checkIns());
    }
}

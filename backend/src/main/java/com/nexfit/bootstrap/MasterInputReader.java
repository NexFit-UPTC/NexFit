package com.nexfit.bootstrap;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.core.env.Environment;

/**
 * Obtiene los datos del Master para la inicialización (RNF-14): primero de variables de entorno y,
 * si falta el correo o la contraseña, de forma interactiva desde la terminal. Nada va en el repositorio.
 *
 * <p>Variables: {@code NEXFIT_MASTER_EMAIL} y {@code NEXFIT_MASTER_PASSWORD} (obligatorias, por entorno
 * o por entrada interactiva) y opcionales {@code NEXFIT_MASTER_DOCUMENT_TYPE}, {@code NEXFIT_MASTER_DOCUMENT_NUMBER},
 * {@code NEXFIT_MASTER_FIRST_NAME}, {@code NEXFIT_MASTER_LAST_NAME} y {@code NEXFIT_MASTER_BIRTH_DATE} (AAAA-MM-DD).
 */
final class MasterInputReader {

    /** Entrada interactiva; es nula cuando no hay una terminal. */
    interface Prompt {
        String ask(String label, boolean secret);
    }

    private final Environment env;
    private final Prompt prompt;

    MasterInputReader(Environment env, Prompt prompt) {
        this.env = env;
        this.prompt = prompt;
    }

    MasterInput read() {
        String email = fromEnv("NEXFIT_MASTER_EMAIL");
        if (email == null) {
            email = interactive("NEXFIT_MASTER_EMAIL", "Correo del Master", false);
        }
        String password = env.getProperty("NEXFIT_MASTER_PASSWORD");
        if (password == null || password.isEmpty()) {
            password = interactive("NEXFIT_MASTER_PASSWORD", "Contraseña temporal del Master", true);
            String again = interactive("NEXFIT_MASTER_PASSWORD", "Repite la contraseña", true);
            if (!password.equals(again)) {
                throw new IllegalArgumentException("Las contraseñas no coinciden.");
            }
        }
        return new MasterInput(
            email.strip(),
            valueOr("NEXFIT_MASTER_DOCUMENT_TYPE", "CC"),
            valueOr("NEXFIT_MASTER_DOCUMENT_NUMBER", "MASTER-0001"),
            valueOr("NEXFIT_MASTER_FIRST_NAME", "Administrador"),
            valueOr("NEXFIT_MASTER_LAST_NAME", "Master"),
            birthDate(),
            password);
    }

    private String fromEnv(String key) {
        String value = env.getProperty(key);
        return value == null || value.isBlank() ? null : value;
    }

    private String valueOr(String key, String fallback) {
        String value = fromEnv(key);
        return value == null ? fallback : value.strip();
    }

    private LocalDate birthDate() {
        String raw = valueOr("NEXFIT_MASTER_BIRTH_DATE", "1970-01-01");
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("NEXFIT_MASTER_BIRTH_DATE debe tener el formato AAAA-MM-DD.");
        }
    }

    private String interactive(String variable, String label, boolean secret) {
        if (prompt == null) {
            throw new IllegalStateException(
                "Falta %s: defínela como variable de entorno o ejecuta desde una terminal interactiva."
                    .formatted(variable));
        }
        String answer = prompt.ask(label, secret);
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("%s no puede estar vacío.".formatted(label));
        }
        return answer;
    }
}

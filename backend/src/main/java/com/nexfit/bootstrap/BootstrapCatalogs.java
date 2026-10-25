package com.nexfit.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lee los catálogos iniciales que viajan en el repositorio (no son secretos):
 * {@code bootstrap/eps.txt} y {@code bootstrap/exercises.txt}.
 */
final class BootstrapCatalogs {

    static final String EPS_FILE = "bootstrap/eps.txt";
    static final String EXERCISES_FILE = "bootstrap/exercises.txt";

    record Exercise(String name, String muscleGroup, String description) { }

    private BootstrapCatalogs() { }

    static List<String> epsNames() {
        return lines(EPS_FILE);
    }

    /** Cada línea: {@code Nombre | Grupo muscular | Descripción}. */
    static List<Exercise> globalExercises() {
        List<String> lines = lines(EXERCISES_FILE);
        return lines.stream().map(line -> {
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new IllegalStateException(
                    "Línea inválida en %s (se espera «Nombre | Grupo muscular | Descripción»): %s"
                        .formatted(EXERCISES_FILE, line));
            }
            return new Exercise(parts[0].strip(), parts[1].strip(), parts[2].strip());
        }).toList();
    }

    private static List<String> lines(String resource) {
        try (InputStream in = BootstrapCatalogs.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("No se encontró el catálogo " + resource);
            }
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el catálogo " + resource, e);
        }
    }
}

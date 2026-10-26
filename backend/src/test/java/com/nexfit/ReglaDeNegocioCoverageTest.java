package com.nexfit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TT-16 (NEX-13): genera un reporte de qué reglas de negocio con mecanismo
 * Backend (SRS 3.4; doc. 04, sección 6) tienen al menos una prueba
 * automática que las cite con {@link ReglaDeNegocio}.
 *
 * <p>No falla porque una regla aún no tenga pruebas: el reporte sirve
 * precisamente para ir viéndolas cubrirse. Sí falla si alguna prueba cita
 * una RN que no está en {@link #REGLAS_BACKEND} (probable error de tipeo
 * o regla que no es de mecanismo Backend), para que esa cobertura no quede
 * registrada en silencio sobre un ID equivocado.
 */
class ReglaDeNegocioCoverageTest {

    /** Reglas de negocio con mecanismo Backend (SRS 3.4; doc. 04, sección 6). */
    private static final List<String> REGLAS_BACKEND = List.of(
        "RN-03", "RN-05", "RN-06", "RN-07", "RN-08", "RN-19",
        "RN-24", "RN-30", "RN-34", "RN-35", "RN-36", "RN-38"
    );

    private static final Path REPORTE = Path.of("target", "rn-coverage-report.md");

    @Test
    void generaElReporteDeCobertura() throws IOException {
        Map<String, List<String>> pruebasPorRegla = new TreeMap<>();
        REGLAS_BACKEND.forEach(rn -> pruebasPorRegla.put(rn, new ArrayList<>()));

        List<String> reglasDesconocidas = new ArrayList<>();

        for (Method metodo : metodosDePruebaAnotados()) {
            String nombrePrueba = metodo.getDeclaringClass().getName() + "#" + metodo.getName();
            for (String rn : metodo.getAnnotation(ReglaDeNegocio.class).value()) {
                if (REGLAS_BACKEND.contains(rn)) {
                    pruebasPorRegla.get(rn).add(nombrePrueba);
                } else {
                    reglasDesconocidas.add(rn + " citada en " + nombrePrueba);
                }
            }
        }

        Files.createDirectories(REPORTE.getParent());
        Files.writeString(REPORTE, construirReporte(pruebasPorRegla));

        assertThat(reglasDesconocidas)
            .as("""
                RN citadas con @ReglaDeNegocio que no son de mecanismo Backend \
                (SRS 3.4) o tienen un ID mal escrito""")
            .isEmpty();
    }

    private String construirReporte(Map<String, List<String>> pruebasPorRegla) {
        StringBuilder reporte = new StringBuilder();
        reporte.append("# Cobertura de pruebas por regla de negocio (TT-16 · NEX-13)\n\n");
        reporte.append("Reglas de negocio con mecanismo Backend (SRS 3.4) y sus pruebas automáticas.\n");
        reporte.append("Generado por `ReglaDeNegocioCoverageTest` en cada `mvn verify`; no editar a mano.\n\n");
        reporte.append("| Regla | Estado | Pruebas |\n|---|---|---|\n");

        pruebasPorRegla.forEach((rn, pruebas) -> {
            String estado = pruebas.isEmpty() ? "Sin cobertura" : "Cubierta";
            String listaPruebas = pruebas.isEmpty() ? "—" : String.join("<br>", pruebas);
            reporte.append("| %s | %s | %s |\n".formatted(rn, estado, listaPruebas));
        });

        long cubiertas = pruebasPorRegla.values().stream().filter(p -> !p.isEmpty()).count();
        reporte.append("\n%d de %d reglas cubiertas.\n".formatted(cubiertas, pruebasPorRegla.size()));
        return reporte.toString();
    }

    /**
     * Recorre las clases ya compiladas en {@code target/test-classes} (incluidas
     * las {@code @Nested}, que compilan a su propio .class) buscando métodos
     * {@code @Test} anotados con {@link ReglaDeNegocio}.
     */
    private List<Method> metodosDePruebaAnotados() throws IOException {
        Path testClasses = Path.of("target", "test-classes");
        if (!Files.isDirectory(testClasses)) {
            return List.of();
        }

        List<Method> encontrados = new ArrayList<>();
        try (Stream<Path> archivos = Files.walk(testClasses)) {
            List<Path> clases = archivos.filter(p -> p.toString().endsWith(".class")).toList();
            for (Path archivo : clases) {
                String nombreClase = nombreBinario(testClasses, archivo);
                if (nombreClase.equals(ReglaDeNegocioCoverageTest.class.getName())) {
                    continue; // no se autoescanea
                }

                Class<?> clase;
                try {
                    clase = Class.forName(nombreClase, false, Thread.currentThread().getContextClassLoader());
                } catch (Throwable noSePudoCargar) {
                    // Clase no cargable en este contexto reflexivo (p. ej. referencia una
                    // dependencia de Testcontainers no inicializada aquí); se ignora.
                    continue;
                }

                for (Method metodo : clase.getDeclaredMethods()) {
                    if (metodo.isAnnotationPresent(ReglaDeNegocio.class)) {
                        encontrados.add(metodo);
                    }
                }
            }
        }
        return encontrados;
    }

    private String nombreBinario(Path raiz, Path archivoClase) {
        String relativo = raiz.relativize(archivoClase).toString();
        String sinExtension = relativo.substring(0, relativo.length() - ".class".length());
        return sinExtension.replace(File.separatorChar, '.');
    }
}
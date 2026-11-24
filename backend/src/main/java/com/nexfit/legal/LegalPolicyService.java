package com.nexfit.legal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * Texto vigente de la política de tratamiento de datos y de la autorización de datos de salud
 * (TT-17, NEX-115). Fuente única: la pantalla de afiliación lo muestra y el servidor sella la
 * versión aceptada sin confiar en lo que mande el cliente. Sin acceso a BD: los textos viven en
 * recursos del classpath y se cargan una sola vez al arrancar; si falta un recurso, la aplicación
 * no arranca, en vez de servir una política a medias.
 *
 * <p>El texto es el borrador 0.1-borrador, sin revisión jurídica y con [CORCHETES] sin resolver:
 * se muestra etiquetado como borrador (decisión registrada en NEX-115) y nunca se inventan los
 * datos que piden los corchetes.
 */
@Service
public class LegalPolicyService {

    /** Versión vigente del texto (TT-17, 0.1-borrador). */
    public static final String VERSION = "0.1-borrador";

    private static final LocalDate EFFECTIVE_DATE = LocalDate.of(2026, 10, 18);
    private static final String STATUS = "borrador";

    private final PolicyDocument document;

    public LegalPolicyService() {
        this.document = new PolicyDocument(VERSION, EFFECTIVE_DATE, STATUS,
            read("/legal/politica-datos-0.1-borrador.txt"),
            read("/legal/autorizacion-salud-0.1-borrador.txt"));
    }

    public PolicyDocument document() {
        return document;
    }

    /** Versión que el servidor sella al registrar cada aceptación. */
    public String currentVersion() {
        return document.version();
    }

    private static String read(String resource) {
        try (InputStream in = LegalPolicyService.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Falta el recurso de la política vigente: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el recurso de la política vigente: " + resource, e);
        }
    }
}

package com.nexfit.legal;

import java.time.LocalDate;

/**
 * Documento vigente de la política de tratamiento de datos y de la autorización de datos de salud.
 * Es el tipo que cruza módulos (lo consumen {@code administration} y {@code membership} para sellar la
 * versión aceptada), así que es un record {@code public} de nivel superior, nunca anidado.
 */
public record PolicyDocument(String version, LocalDate effectiveDate, String status,
                             String privacyText, String healthText) {
}

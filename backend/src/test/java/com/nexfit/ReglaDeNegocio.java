package com.nexfit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un método {@code @Test} que verifica una o más reglas de negocio (RN)
 * del SRS. {@link ReglaDeNegocioCoverageTest} escanea esta anotación para
 * generar el reporte de cobertura (TT-16, NEX-13).
 *
 * <p>Ejemplo:
 * <pre>{@code
 * @Test
 * @ReglaDeNegocio("RN-34")
 * void elStaffNoPuedeModificarDatosDeIdentidad() { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReglaDeNegocio {

    /** IDs de las reglas de negocio que esta prueba verifica, p. ej. {"RN-34"}. */
    String[] value();
}
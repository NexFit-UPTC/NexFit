package com.nexfit.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint sin regla de rol: basta una sesión válida (la exige SecurityConfig). Para rutas que no
 * dependen de la matriz 3.7, como las de /auth. No exime de filtrar los datos por establecimiento.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AnyAuthenticated {
}

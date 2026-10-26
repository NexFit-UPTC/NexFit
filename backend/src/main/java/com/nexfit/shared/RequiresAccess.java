package com.nexfit.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Regla explícita de un endpoint según la matriz 3.7: solo pasan los roles que la matriz permite
 * para esa función y acción (unión de roles, RN-03). Sin esta anotación ni {@link AnyAuthenticated}
 * el endpoint responde 403 (default-deny). En el método tiene prioridad sobre la de la clase.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresAccess {
    Funcion funcion();

    Accion accion();
}

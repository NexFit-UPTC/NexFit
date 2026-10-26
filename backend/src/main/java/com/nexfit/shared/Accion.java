package com.nexfit.shared;

/** Acciones de la matriz 3.7. Las celdas "R (agregado)" y "R (globales)" del master son CONSULTAR_AGREGADO. */
public enum Accion {
    CREAR,
    CONSULTAR,
    /** Solo cifras agregadas; nunca datos de clientes (RN-08). */
    CONSULTAR_AGREGADO,
    MODIFICAR,
    /** Activar o desactivar ("A"). */
    ACTIVAR,
    ANULAR,
    COMPLETAR,
    RENOVAR,
    SUSPENDER,
    CANCELAR,
    /** Restablecer la contraseña de otra cuenta. */
    RESTABLECER,
    /** Cambiar la contraseña propia. */
    CAMBIAR_PROPIA
}

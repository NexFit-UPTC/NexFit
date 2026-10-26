package com.nexfit.shared;

/** Las 21 funciones (filas) de la matriz de permisos 3.7 del SRS. */
public enum Funcion {
    ESTABLECIMIENTOS,
    CUENTA_ADMINISTRADOR,
    SEDES,
    PERSONAL_Y_ROLES,
    /** Clientes (datos del establecimiento). */
    CLIENTES,
    PLANES,
    MEMBRESIAS,
    PAGOS,
    CLASES,
    RESERVAS,
    ASISTENCIA,
    ASIGNACION_ENTRENADOR_ALUMNO,
    RUTINAS,
    /** Entrenamientos, racha, meta y peso. */
    ENTRENAMIENTOS,
    CATALOGO_EJERCICIOS,
    /** EPS y contactos de emergencia. */
    EPS_Y_CONTACTOS,
    CATALOGO_EPS,
    EQUIPO,
    /** Indicadores y paneles. */
    INDICADORES,
    AUDITORIA,
    CONTRASENAS
}

package com.nexfit.shared;

import static com.nexfit.shared.Accion.*;
import static com.nexfit.shared.Funcion.*;
import static com.nexfit.shared.Rol.*;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;

/**
 * Matriz de permisos 3.7 del SRS: función + acción → roles permitidos. Es solo la compuerta por
 * rol; las reglas de datos de cada historia (alumnos asignados, recursos propios) las aplica
 * cada historia. Lo que la matriz marca con "—" no está aquí, por lo que queda denegado.
 */
public final class Permisos {

    private static final Map<Funcion, Map<Accion, Set<Rol>>> MATRIZ = new EnumMap<>(Funcion.class);

    static {
        dar(ESTABLECIMIENTOS, MASTER, CREAR, CONSULTAR, MODIFICAR, ACTIVAR);
        dar(ESTABLECIMIENTOS, ADMIN, CONSULTAR);

        dar(CUENTA_ADMINISTRADOR, MASTER, CREAR, MODIFICAR);
        dar(CUENTA_ADMINISTRADOR, MASTER, CONSULTAR);


        dar(SEDES, MASTER, CONSULTAR_AGREGADO);
        dar(SEDES, ADMIN, CREAR, CONSULTAR, MODIFICAR, ACTIVAR);
        dar(SEDES, STAFF, CONSULTAR);
        dar(SEDES, TRAINER, CONSULTAR);
        dar(SEDES, CLIENT, CONSULTAR);

        dar(PERSONAL_Y_ROLES, ADMIN, CREAR, CONSULTAR, MODIFICAR, ACTIVAR);

        dar(CLIENTES, ADMIN, CONSULTAR, ACTIVAR);
        dar(CLIENTES, STAFF, CREAR, CONSULTAR, MODIFICAR, ACTIVAR);
        dar(CLIENTES, TRAINER, CONSULTAR);
        dar(CLIENTES, CLIENT, CONSULTAR, MODIFICAR);

        dar(PLANES, ADMIN, CREAR, CONSULTAR, MODIFICAR, ACTIVAR);
        dar(PLANES, STAFF, CONSULTAR);
        dar(PLANES, CLIENT, CONSULTAR);

        dar(MEMBRESIAS, MASTER, CONSULTAR_AGREGADO);
        dar(MEMBRESIAS, ADMIN, CONSULTAR, SUSPENDER, CANCELAR);
        dar(MEMBRESIAS, STAFF, CREAR, CONSULTAR, RENOVAR, SUSPENDER, CANCELAR);
        dar(MEMBRESIAS, CLIENT, CONSULTAR);

        dar(PAGOS, ADMIN, CONSULTAR, ANULAR, COMPLETAR);
        dar(PAGOS, STAFF, CREAR, CONSULTAR, ANULAR, COMPLETAR);
        dar(PAGOS, CLIENT, CONSULTAR);

        dar(CLASES, ADMIN, CREAR, CONSULTAR, MODIFICAR, CANCELAR);
        dar(CLASES, STAFF, CONSULTAR);
        dar(CLASES, TRAINER, CONSULTAR);
        dar(CLASES, CLIENT, CONSULTAR);

        dar(RESERVAS, ADMIN, CONSULTAR);
        dar(RESERVAS, STAFF, CREAR, CONSULTAR, CANCELAR);
        dar(RESERVAS, TRAINER, CONSULTAR);
        dar(RESERVAS, CLIENT, CREAR, CONSULTAR, CANCELAR);

        dar(ASISTENCIA, ADMIN, CONSULTAR);
        dar(ASISTENCIA, STAFF, CREAR, CONSULTAR, MODIFICAR);
        dar(ASISTENCIA, TRAINER, CONSULTAR);
        dar(ASISTENCIA, CLIENT, CREAR, CONSULTAR, MODIFICAR);

        dar(ASIGNACION_ENTRENADOR_ALUMNO, ADMIN, CREAR, CONSULTAR, MODIFICAR);
        dar(ASIGNACION_ENTRENADOR_ALUMNO, STAFF, CREAR, CONSULTAR, MODIFICAR);
        dar(ASIGNACION_ENTRENADOR_ALUMNO, TRAINER, CONSULTAR);

        // RN-24: solo el entrenador y el cliente; ni admin, ni staff, ni master.
        dar(RUTINAS, TRAINER, CREAR, CONSULTAR, MODIFICAR);
        dar(RUTINAS, CLIENT, CONSULTAR);

        dar(ENTRENAMIENTOS, CLIENT, CREAR, CONSULTAR, MODIFICAR);

        dar(CATALOGO_EJERCICIOS, MASTER, CREAR, CONSULTAR, MODIFICAR, ACTIVAR);
        dar(CATALOGO_EJERCICIOS, ADMIN, CREAR, CONSULTAR, MODIFICAR);
        dar(CATALOGO_EJERCICIOS, STAFF, CONSULTAR);
        dar(CATALOGO_EJERCICIOS, TRAINER, CREAR, CONSULTAR);
        dar(CATALOGO_EJERCICIOS, CLIENT, CONSULTAR);

        dar(EPS_Y_CONTACTOS, ADMIN, CONSULTAR);
        dar(EPS_Y_CONTACTOS, STAFF, CREAR, CONSULTAR, MODIFICAR);
        dar(EPS_Y_CONTACTOS, TRAINER, CONSULTAR);
        dar(EPS_Y_CONTACTOS, CLIENT, CREAR, CONSULTAR, MODIFICAR);

        dar(CATALOGO_EPS, MASTER, CREAR, CONSULTAR, MODIFICAR, ACTIVAR);
        dar(CATALOGO_EPS, ADMIN, CONSULTAR);
        dar(CATALOGO_EPS, STAFF, CONSULTAR);
        dar(CATALOGO_EPS, CLIENT, CONSULTAR);

        dar(EQUIPO, ADMIN, CREAR, CONSULTAR, MODIFICAR);
        dar(EQUIPO, STAFF, CONSULTAR);

        dar(INDICADORES, MASTER, CONSULTAR_AGREGADO);
        dar(INDICADORES, ADMIN, CONSULTAR);

        dar(AUDITORIA, MASTER, CONSULTAR);
        dar(AUDITORIA, ADMIN, CONSULTAR);

        // La matriz abrevia: "Cambiar la propia" figura solo en Entrenador y Cliente, pero todos los
        // roles cambian su propia contraseña (RF-102, HU-46).
        dar(CONTRASENAS, MASTER, RESTABLECER, CAMBIAR_PROPIA);
        dar(CONTRASENAS, ADMIN, RESTABLECER, CAMBIAR_PROPIA);
        dar(CONTRASENAS, STAFF, RESTABLECER, CAMBIAR_PROPIA);
        dar(CONTRASENAS, TRAINER, CAMBIAR_PROPIA);
        dar(CONTRASENAS, CLIENT, CAMBIAR_PROPIA);
    }

    private Permisos() {
    }

    private static void dar(Funcion funcion, Rol rol, Accion... acciones) {
        for (Accion accion : acciones) {
            MATRIZ.computeIfAbsent(funcion, f -> new EnumMap<>(Accion.class))
                .computeIfAbsent(accion, a -> EnumSet.noneOf(Rol.class)).add(rol);
        }
    }

    /** Roles que la matriz autoriza para esa función y acción; vacío si nadie. */
    public static Set<Rol> rolesPermitidos(Funcion funcion, Accion accion) {
        return Set.copyOf(MATRIZ.getOrDefault(funcion, Map.of()).getOrDefault(accion, Set.of()));
    }

    /** ¿Alguna de las autoridades (unión de roles, RN-03) tiene un rol permitido para esa función y acción? */
    public static boolean permite(Funcion funcion, Accion accion,
                                  Collection<? extends GrantedAuthority> autoridades) {
        Set<Rol> permitidos = rolesPermitidos(funcion, accion);
        return autoridades.stream()
            .map(a -> Rol.deAutoridad(a.getAuthority()))
            .flatMap(Optional::stream)
            .anyMatch(permitidos::contains);
    }
}

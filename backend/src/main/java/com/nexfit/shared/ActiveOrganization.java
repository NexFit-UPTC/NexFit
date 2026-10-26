package com.nexfit.shared;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Establecimiento activo de la solicitud. Sale de las autoridades "rol:orgId" que SessionUserFilter
 * arma en cada solicitud con la sesión y la BD; nunca de un parámetro, cuerpo o cabecera del cliente.
 * Úsalo en toda consulta: {@code WHERE id = :id AND organization_id = :org}, y si no hay fila, 404
 * (el mismo de un id inexistente, para no revelar recursos de otro gimnasio).
 */
public final class ActiveOrganization {

    private ActiveOrganization() {
    }

    /** @throws AccessDeniedException (403) si la solicitud no opera dentro de un establecimiento (p. ej. el master). */
    public static long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            for (GrantedAuthority authority : auth.getAuthorities()) {
                String value = authority.getAuthority();
                int sep = value.indexOf(':');
                if (sep > 0 && Rol.deAutoridad(value).isPresent()) {
                    return Long.parseLong(value.substring(sep + 1));
                }
            }
        }
        throw new AccessDeniedException("Sin establecimiento activo");
    }
}

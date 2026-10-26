package com.nexfit.shared;

import java.util.Locale;
import java.util.Optional;

/** Roles de la matriz 3.7. MASTER es "PLATFORM_ADMIN"; los demás llegan como autoridad "rol:orgId" (RN-03). */
public enum Rol {
    MASTER, ADMIN, STAFF, TRAINER, CLIENT;

    static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** Rol que representa una autoridad de la sesión; vacío si no es de ninguno. */
    public static Optional<Rol> deAutoridad(String authority) {
        if (PLATFORM_ADMIN.equals(authority)) {
            return Optional.of(MASTER);
        }
        int sep = authority.indexOf(':');
        if (sep < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(authority.substring(0, sep).toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

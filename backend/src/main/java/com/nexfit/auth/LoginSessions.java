package com.nexfit.auth;

import com.nexfit.auth.AuthService.LoginResult;
import com.nexfit.shared.SessionUserFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * Apertura de la sesión tras un inicio de sesión válido. La comparten el login con
 * contraseña y el de Google (NEX-147) para que ambos dejen la misma sesión.
 */
final class LoginSessions {

    private LoginSessions() {
    }

    /** Invalida la sesión previa (anti-fijación) y abre una nueva con el usuario y, si procede, su gimnasio. */
    static void open(HttpServletRequest request, LoginResult r) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SessionUserFilter.USER_ID_ATTRIBUTE, r.userId());
        // Permite a Spring Session encontrar todas las sesiones de un usuario (para
        // revocarlas al cambiar la contraseña).
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                String.valueOf(r.userId()));
        // US-05: con una sola afiliación activa se elige sola; con varias, el cliente
        // elige.
        if (r.establishments().size() == 1) {
            session.setAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE,
                    r.establishments().get(0).organizationId());
        }
    }
}

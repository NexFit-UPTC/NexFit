package com.nexfit.auth;

import com.nexfit.auth.AuthService.LoginResult;
import com.nexfit.shared.SessionUserFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * Éxito del inicio de sesión con Google (NEX-147): el correo ya viene
 * verificado por Google, así que solo se enlaza con la cuenta NexFit que
 * tenga ese correo. La sesión que se abre es la misma del login con
 * contraseña (NEXFIT_USER_ID + auto-selección de establecimiento); los
 * roles y permisos se siguen armando desde la BD en cada solicitud.
 *
 * <p>Sin cuenta (o cuenta no elegible) no se abre sesión: se vuelve al login
 * con un error genérico, sin revelar si el correo existe.
 */
class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService auth;
    private final RedirectStrategy redirectStrategy;

    GoogleLoginSuccessHandler(AuthService auth) {
        this(auth, contextRelativeRedirect());
    }

    GoogleLoginSuccessHandler(AuthService auth, RedirectStrategy redirectStrategy) {
        this.auth = auth;
        this.redirectStrategy = redirectStrategy;
    }

    /** Redirección relativa al host público (sin el context-path /api): el
     * flujo vuelve por el proxy del front, así que la URL debe resolverla el
     * navegador contra la dirección que ve, no contra la del backend. */
    static RedirectStrategy contextRelativeRedirect() {
        var strategy = new DefaultRedirectStrategy();
        strategy.setContextRelative(true);
        return strategy;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        Optional<LoginResult> result = verifiedEmail(authentication)
                .flatMap(email -> auth.loginWithGoogle(email, request.getRemoteAddr()));
        if (result.isEmpty()) {
            redirectStrategy.sendRedirect(request, response, "/login?error=google");
            return;
        }
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        LoginResult r = result.get();
        session.setAttribute(SessionUserFilter.USER_ID_ATTRIBUTE, r.userId());
        // Permite a Spring Session encontrar todas las sesiones de un usuario (para
        // revocarlas al cambiar la contraseña).
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                String.valueOf(r.userId()));
        // US-05: con una sola afiliación activa se elige sola; con varias, el cliente
        // elige. Igual que en AuthController.login.
        if (r.establishments().size() == 1) {
            session.setAttribute(SessionUserFilter.ORG_ID_ATTRIBUTE,
                    r.establishments().get(0).organizationId());
        }
        redirectStrategy.sendRedirect(request, response, "/login");
    }

    /**
     * Correo verificado por el proveedor. Sin verificación no hay identidad
     * confiable: se rechaza sin tocar la BD.
     */
    static Optional<String> verifiedEmail(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcUser oidc) {
            if (!Boolean.TRUE.equals(oidc.getEmailVerified())) {
                return Optional.empty();
            }
            return Optional.ofNullable(oidc.getEmail());
        }
        if (principal instanceof OAuth2User oauth2) {
            Object verified = oauth2.getAttribute("email_verified");
            if (verified instanceof Boolean checked && !checked) {
                return Optional.empty();
            }
            return Optional.ofNullable(oauth2.getAttribute("email"));
        }
        return Optional.empty();
    }
}

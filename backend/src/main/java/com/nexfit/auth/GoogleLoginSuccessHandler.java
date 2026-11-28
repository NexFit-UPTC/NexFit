package com.nexfit.auth;

import com.nexfit.auth.AuthService.GoogleLoginResult;
import com.nexfit.shared.SessionUserFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Google ya autenticó a la persona (NEX-147); aquí solo se enlaza su correo verificado con la
 * cuenta de NexFit que tenga ese correo. La sesión que se abre es la del login con contraseña
 * ({@link LoginSessions}); roles y permisos se siguen armando desde la BD en cada solicitud.
 *
 * <p>Sin correo verificado o con una cuenta no elegible (inactiva, bloqueada, Master o con contraseña
 * temporal pendiente) no se abre sesión y se vuelve al login con el mismo error genérico, sin revelar
 * el motivo. Un correo verificado sin cuenta (NEX-148, NEX-149) deja un registro pendiente en la sesión
 * (con el nombre y el apellido del perfil de Google) y va directo a {@code /completar-registro}; decirlo
 * no filtra nada: la persona acaba de probar con Google que el correo es suyo. Una sesión previa solo se
 * reemplaza cuando el ingreso es válido.
 */
class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService auth;
    private final RedirectStrategy redirect;

    GoogleLoginSuccessHandler(AuthService auth) {
        this(auth, contextRelativeRedirect());
    }

    GoogleLoginSuccessHandler(AuthService auth, RedirectStrategy redirect) {
        this.auth = auth;
        this.redirect = redirect;
    }

    /**
     * Redirige a una ruta del sitio público (sin el context-path /api): el navegador la resuelve
     * contra el host que ve, el de Vercel, y no contra el del backend.
     */
    static RedirectStrategy contextRelativeRedirect() {
        var strategy = new DefaultRedirectStrategy();
        strategy.setContextRelative(true);
        return strategy;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        // Lo que Google devolvió no debe quedar como autenticación de esta solicitud.
        SecurityContextHolder.clearContext();
        // Sin correo verificado se consulta con null: no hay cuenta, y el rechazo queda auditado igual.
        String email = verifiedEmail(authentication).orElse(null);
        GoogleLoginResult result = auth.loginWithGoogle(email, request.getRemoteAddr());
        switch (result.status()) {
            case LOGGED_IN -> {
                LoginSessions.open(request, result.login());
                redirect.sendRedirect(request, response, GoogleLoginConfig.SUCCESS_URL);
            }
            case NO_ACCOUNT -> {
                // NEX-149: correo verificado sin cuenta → directo a completar los datos. Con una sesión de
                // usuario ya abierta no se ofrece (rechazo genérico, sin tocar esa sesión).
                HttpSession previous = request.getSession(false);
                if (previous != null && previous.getAttribute(SessionUserFilter.USER_ID_ATTRIBUTE) != null) {
                    redirect.sendRedirect(request, response, GoogleLoginConfig.FAILURE_URL);
                    return;
                }
                GooglePendingRegistration.start(request, email, givenName(authentication),
                        familyName(authentication));
                redirect.sendRedirect(request, response, GoogleLoginConfig.NEW_ACCOUNT_URL);
            }
            case REJECTED -> redirect.sendRedirect(request, response, GoogleLoginConfig.FAILURE_URL);
        }
    }

    /** Correo del ID token de Google, solo si Google lo marca verificado. Cualquier otra cosa, vacío. */
    static Optional<String> verifiedEmail(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidc
                && Boolean.TRUE.equals(oidc.getEmailVerified())) {
            String email = oidc.getEmail();
            return email == null || email.isBlank() ? Optional.empty() : Optional.of(email);
        }
        return Optional.empty();
    }

    /** Nombre del perfil de Google (alcance {@code profile}); null si no viene. Solo precarga un formulario. */
    private static String givenName(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof OidcUser oidc
                ? oidc.getGivenName() : null;
    }

    private static String familyName(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof OidcUser oidc
                ? oidc.getFamilyName() : null;
    }
}

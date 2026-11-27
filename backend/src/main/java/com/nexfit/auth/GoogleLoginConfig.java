package com.nexfit.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Inicio de sesión con Google (NEX-147), en una cadena aparte que solo atiende
 * {@code /oauth2/authorization/**} y {@code /login/oauth2/code/**} (con el context-path,
 * {@code /api/...}). No agrega controladores: esos endpoints los gestiona Spring Security.
 *
 * <p>Solo existe si GOOGLE_CLIENT_ID y GOOGLE_CLIENT_SECRET tienen valor; sin ellas la
 * aplicación arranca igual y esas rutas caen en la regla general (401). Nada de esto se
 * guarda en el repositorio (ver .env.example).
 *
 * <p>El contexto de seguridad de OAuth2 no se persiste en la sesión
 * ({@link NullSecurityContextRepository}): la sesión de NexFit la abre
 * {@link GoogleLoginSuccessHandler} solo si el correo enlaza con una cuenta elegible, y
 * un correo de Google sin cuenta no debe quedar "autenticado" ante el resto de la API.
 */
@Configuration
@ConditionalOnExpression("'${GOOGLE_CLIENT_ID:}' != '' && '${GOOGLE_CLIENT_SECRET:}' != ''")
class GoogleLoginConfig {

    static final String FAILURE_URL = "/login?error=google";
    static final String SUCCESS_URL = "/login?google=1";
    /** Correo verificado por Google sin cuenta (NEX-148): el login ofrece crearla. */
    static final String NEW_ACCOUNT_URL = "/login?google=nuevo";
    private static final String CALLBACK_PATH = "/api/login/oauth2/code/google";

    @Bean
    ClientRegistrationRepository googleClientRegistrationRepository(
            @Value("${GOOGLE_CLIENT_ID}") String clientId,
            @Value("${GOOGLE_CLIENT_SECRET}") String clientSecret,
            @Value("${NEXFIT_PUBLIC_URL:http://localhost:4200}") String publicUrl) {
        // La redirect URI sale de la configuración y no de la solicitud: tras el proxy de
        // Vercel el host que ve el backend puede ser el de Azure, no el público.
        String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        var registration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope("openid", "email")
                .redirectUri(base + CALLBACK_PATH)
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    GoogleLoginSuccessHandler googleLoginSuccessHandler(AuthService auth) {
        return new GoogleLoginSuccessHandler(auth);
    }

    @Bean
    @Order(1)
    SecurityFilterChain googleLoginFilterChain(HttpSecurity http, GoogleLoginSuccessHandler successHandler,
            @Value("${nexfit.auth.google-max-per-hour:120}") int maxPerHour) throws Exception {
        RedirectStrategy redirect = GoogleLoginSuccessHandler.contextRelativeRedirect();
        http
                .securityMatcher("/oauth2/authorization/**", "/login/oauth2/code/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .securityContext(sc -> sc.securityContextRepository(new NullSecurityContextRepository()))
                .oauth2Login(oauth -> oauth
                        .securityContextRepository(new NullSecurityContextRepository())
                        .authorizedClientRepository(new DiscardAuthorizedClientRepository())
                        .successHandler(successHandler)
                        // Sin guardar la excepción en la sesión y sin detalle: todo fallo es igual.
                        .failureHandler((request, response, exception) -> redirect.sendRedirect(request, response,
                                FAILURE_URL)))
                .addFilterBefore(new RateLimitFilter(new RegisterRateLimiter(maxPerHour), redirect),
                        OAuth2AuthorizationRequestRedirectFilter.class);
        return http.build();
    }

    /**
     * Google solo se usa para identificar a la persona: no se conserva el token de acceso en la sesión.
     */
    static final class DiscardAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }
    }

    /**
     * Tope por conexión: cada GET anónimo a la autorización crea una sesión en la base, y el
     * callback no debe poder martillarse. Al excederse vuelve al login con el mismo error
     * genérico (es una navegación del navegador, no una llamada de API).
     */
    static final class RateLimitFilter extends OncePerRequestFilter {

        private final RegisterRateLimiter limiter;
        private final RedirectStrategy redirect;

        RateLimitFilter(RegisterRateLimiter limiter, RedirectStrategy redirect) {
            this.limiter = limiter;
            this.redirect = redirect;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            if (!limiter.allow(request.getRemoteAddr())) {
                redirect.sendRedirect(request, response, FAILURE_URL);
                return;
            }
            chain.doFilter(request, response);
        }
    }

}

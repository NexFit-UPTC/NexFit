package com.nexfit.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

/**
 * Inicio de sesión con Google (NEX-147), en cadena aparte para no tocar la
 * configuración principal de {@code shared}: solo atiende la solicitud de
 * autorización y el callback de OAuth2, y no agrega controladores (esos
 * endpoints los gestiona Spring Security).
 *
 * <p>La cadena solo existe si GOOGLE_CLIENT_ID tiene valor: sin credenciales
 * de Google la aplicación arranca igual. Las credenciales llegan por
 * variables de entorno, nunca en el repo (ver .env.example).
 */
@Configuration
@ConditionalOnExpression("'${GOOGLE_CLIENT_ID:}' != ''")
class GoogleLoginConfig {

    @Bean
    ClientRegistrationRepository googleClientRegistrationRepository(
            @Value("${GOOGLE_CLIENT_ID:}") String clientId,
            @Value("${GOOGLE_CLIENT_SECRET:}") String clientSecret) {
        // Scopes y URLs del proveedor por defecto (openid, email, profile);
        // la redirect URI por defecto es {baseUrl}/login/oauth2/code/{registrationId},
        // que con el context-path /api es /api/login/oauth2/code/google.
        var registration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    GoogleLoginSuccessHandler googleLoginSuccessHandler(AuthService auth) {
        return new GoogleLoginSuccessHandler(auth);
    }

    @Bean
    @Order(1)
    SecurityFilterChain googleLoginFilterChain(HttpSecurity http,
                                                GoogleLoginSuccessHandler successHandler) throws Exception {
        var failureHandler = new SimpleUrlAuthenticationFailureHandler("/login?error=google");
        var redirectStrategy = new DefaultRedirectStrategy();
        redirectStrategy.setContextRelative(true);
        failureHandler.setRedirectStrategy(redirectStrategy);
        http
                .securityMatcher("/oauth2/authorization/**", "/login/oauth2/code/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .successHandler(successHandler)
                        .failureHandler(failureHandler));
        return http.build();
    }
}

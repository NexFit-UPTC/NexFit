package com.nexfit.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registro pendiente con Google (NEX-148): el correo que Google verificó y que todavía no tiene
 * cuenta, guardado en la sesión del servidor entre el callback y el formulario de /registro (nunca
 * en la URL). Es un atributo de una sesión sin {@code NEXFIT_USER_ID}, así que no autentica a nadie
 * ni da acceso a nada. Vence a los 10 minutos y se consume al registrar. El valor es un mapa de
 * tipos del JDK para que la serialización de la sesión no dependa de una clase de la aplicación.
 */
final class GooglePendingRegistration {

    static final String ATTRIBUTE = "NEXFIT_GOOGLE_PENDING";
    static final Duration TTL = Duration.ofMinutes(10);
    private static final String EMAIL = "email";
    private static final String EXPIRES_AT = "expiresAt";

    private GooglePendingRegistration() {
    }

    /** Valor del atributo; también lo usan las pruebas para armar un pendiente vencido. */
    static HashMap<String, Object> value(String email, long expiresAtMillis) {
        HashMap<String, Object> value = new HashMap<>();
        value.put(EMAIL, email);
        value.put(EXPIRES_AT, expiresAtMillis);
        return value;
    }

    /**
     * Guarda el pendiente en una sesión con id nuevo (anti-fijación). Quien llama ya comprobó que la
     * sesión no tiene usuario.
     */
    static void start(HttpServletRequest request, String email) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(ATTRIBUTE,
                value(email.trim().toLowerCase(Locale.ROOT), System.currentTimeMillis() + TTL.toMillis()));
    }

    /** Correo pendiente de esta sesión; vacío si no hay o venció (y en ese caso se borra). */
    static Optional<String> peek(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object raw = session == null ? null : session.getAttribute(ATTRIBUTE);
        if (!(raw instanceof Map<?, ?> map)
                || !(map.get(EMAIL) instanceof String email)
                || !(map.get(EXPIRES_AT) instanceof Long expiresAt)) {
            return Optional.empty();
        }
        if (expiresAt <= System.currentTimeMillis()) {
            session.removeAttribute(ATTRIBUTE);
            return Optional.empty();
        }
        return Optional.of(email);
    }

    static void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
        }
    }
}

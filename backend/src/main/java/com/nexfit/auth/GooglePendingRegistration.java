package com.nexfit.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registro pendiente con Google (NEX-148, NEX-149): el correo que Google verificó (con el nombre y el apellido
 * de su perfil) y que todavía no tiene cuenta, guardado en la sesión del servidor entre el callback y la
 * pantalla /completar-registro (nunca en la URL). Es un atributo de una sesión sin {@code NEXFIT_USER_ID}, así
 * que no autentica a nadie ni da acceso a nada. Vence a los 30 minutos contados desde el callback y solo se
 * consume al crear la cuenta: un rechazo (documento o correo repetido) no lo borra. El valor es un mapa de
 * tipos del JDK para que la serialización de la sesión no dependa de una clase de la aplicación.
 */
final class GooglePendingRegistration {

    static final String ATTRIBUTE = "NEXFIT_GOOGLE_PENDING";
    static final Duration TTL = Duration.ofMinutes(30);
    /** Mismo tope que el nombre y el apellido del registro (columnas de 100 caracteres). */
    static final int NAME_MAX = 100;
    private static final String EMAIL = "email";
    private static final String FIRST_NAME = "firstName";
    private static final String LAST_NAME = "lastName";
    private static final String EXPIRES_AT = "expiresAt";

    private GooglePendingRegistration() {
    }

    /** Lo que Google entregó y espera el formulario; los nombres pueden venir vacíos. */
    record Pending(String email, String firstName, String lastName) {
    }

    /** Valor del atributo; también lo usan las pruebas para armar un pendiente vencido. */
    static HashMap<String, Object> value(String email, String firstName, String lastName, long expiresAtMillis) {
        HashMap<String, Object> value = new HashMap<>();
        value.put(EMAIL, email);
        value.put(FIRST_NAME, firstName);
        value.put(LAST_NAME, lastName);
        value.put(EXPIRES_AT, expiresAtMillis);
        return value;
    }

    /**
     * Guarda el pendiente en una sesión con id nuevo (anti-fijación). Quien llama ya comprobó que la
     * sesión no tiene usuario.
     */
    static void start(HttpServletRequest request, String email, String firstName, String lastName) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(ATTRIBUTE, value(email.trim().toLowerCase(Locale.ROOT), cleanName(firstName),
                cleanName(lastName), System.currentTimeMillis() + TTL.toMillis()));
    }

    /** Nombre recortado y limitado a {@link #NAME_MAX}; nulo se vuelve vacío. */
    static String cleanName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        return trimmed.length() > NAME_MAX ? trimmed.substring(0, NAME_MAX).trim() : trimmed;
    }

    /** Pendiente de esta sesión; vacío si no hay o venció (y en ese caso se borra). */
    static Optional<Pending> peek(HttpServletRequest request) {
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
        return Optional.of(new Pending(email, text(map.get(FIRST_NAME)), text(map.get(LAST_NAME))));
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }
}

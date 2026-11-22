package com.nexfit.auth;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tope de registros públicos por conexión: {@code POST /auth/register} no exige sesión y sin límite permitiría
 * crear cuentas en masa. Ventana deslizante de una hora por IP, en memoria (por instancia). Cuenta cada intento,
 * también los rechazados, para no servir de oráculo de correos o documentos existentes. El login ya se protege con
 * el bloqueo de cuenta (US-03).
 */
@Component
class RegisterRateLimiter {

    static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED_IPS = 10_000;

    private final int maxPerWindow;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    RegisterRateLimiter(@Value("${nexfit.auth.register-max-per-hour:30}") int maxPerWindow) {
        this.maxPerWindow = maxPerWindow;
    }

    /** Registra el intento y dice si todavía está dentro del tope. */
    boolean allow(String ip) {
        long now = System.currentTimeMillis();
        long since = now - WINDOW.toMillis();
        if (attempts.size() > MAX_TRACKED_IPS) {
            attempts.values().removeIf(q -> {
                synchronized (q) {
                    return q.isEmpty() || q.peekLast() < since;
                }
            });
        }
        Deque<Long> queue = attempts.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() < since) {
                queue.pollFirst();
            }
            if (queue.size() >= maxPerWindow) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }
}

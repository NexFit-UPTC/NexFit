package com.nexfit.auth;

import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inicio y cierre de sesión (US-01), bloqueo por intentos fallidos (US-03, RN-38),
 * selección de establecimiento (US-05, NEX-24) y restablecimiento asistido de
 * contraseña (US-04).
 * No lanza excepciones por credenciales inválidas: así la auditoría se confirma.
 */
@Service
public class AuthService {

    /** Fallos consecutivos que bloquean la cuenta (RN-38). */
    static final int MAX_FAILED_ATTEMPTS = 5;
    /** Duración del bloqueo automático (RN-38). */
    static final int LOCK_MINUTES = 15;
    /** Largo mínimo de una contraseña nueva (RNF-06). */
    static final int MIN_PASSWORD_LENGTH = 10;
    /** Vigencia de la contraseña temporal generada en un restablecimiento asistido. */
    static final int TEMP_PASSWORD_HOURS = 72;
    /** Largo de la contraseña temporal generada (≥ MIN_PASSWORD_LENGTH). */
    static final int TEMP_PASSWORD_LENGTH = 12;

    /** Alfabeto de la contraseña temporal; excluye caracteres ambiguos (I, l, O, 0, 1). */
    private static final char[] TEMP_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final FindByIndexNameSessionRepository<?> sessions;
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();

    AuthService(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit,
                FindByIndexNameSessionRepository<?> sessions) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.sessions = sessions;
        this.dummyHash = encoder.encode("nexfit-dummy");
    }

    private record Candidate(long id, String passwordHash, boolean usable, boolean mustChangePassword) { }

    /** Establecimiento donde el usuario puede operar: id, nombre y sus roles en él. */
    public record Establishment(long organizationId, String name, List<String> roles) { }

    /** Resultado de un inicio de sesión válido. */
    public record LoginResult(long userId, boolean mustChangePassword, List<Establishment> establishments) { }

    /** Resultado de {@link #changePassword}. */
    public enum PasswordChangeResult { CHANGED, TOO_SHORT, WRONG_CURRENT, SAME_AS_CURRENT }

    /** Estado de {@link #resetPassword}. */
    public enum ResetStatus { OK, TARGET_NOT_FOUND, FORBIDDEN, MASTER_REQUIRED }

    /** Resultado de {@link #resetPassword}; la contraseña temporal solo viene con {@code OK}. */
    public record ResetResult(ResetStatus status, String temporaryPassword) { }

    /** @return el usuario si las credenciales son válidas; vacío en cualquier otro caso. */
    @Transactional
    public Optional<LoginResult> login(String email, String password, String ip) {
        // "usable" = activa, sin bloqueo vigente y, si la contraseña es temporal, con vigencia sin vencer (US-02).
        Optional<Candidate> found = email == null ? Optional.empty() : jdbc.sql("""
                SELECT id, password_hash, must_change_password,
                       is_active
                       AND (locked_until IS NULL OR locked_until <= now())
                       AND NOT (must_change_password
                                AND temp_password_expires_at IS NOT NULL
                                AND temp_password_expires_at <= now()) AS usable
                FROM users WHERE lower(email) = lower(:email)
                """)
            .param("email", email.trim())
            .query((rs, n) -> new Candidate(rs.getLong("id"), rs.getString("password_hash"),
                rs.getBoolean("usable"), rs.getBoolean("must_change_password")))
            .optional();
        // Se compara siempre contra un hash para que el tiempo no delate si el correo existe.
        boolean matches = encoder.matches(password == null ? "" : password,
            found.map(Candidate::passwordHash).orElse(dummyHash));
        if (found.isPresent() && matches && found.get().usable()) {
            long id = found.get().id();
            resetFailedAttempts(id);
            audit.record(AuditEventType.LOGIN, id, null, "user", id, ip, null);
            return Optional.of(new LoginResult(id, found.get().mustChangePassword(), establishments(id)));
        }
        Long id = found.map(Candidate::id).orElse(null);
        audit.record(AuditEventType.LOGIN_FAILED, id, null, id == null ? null : "user", id, ip, null);
        // Solo cuentan los fallos de cuentas activas y no bloqueadas: un intento durante el bloqueo
        // no suma ni lo alarga (si lo alargara, cualquiera podría mantener bloqueada a otra persona).
        if (found.isPresent() && found.get().usable() && registerFailedAttempt(found.get().id())) {
            audit.record(AuditEventType.ACCOUNT_LOCKED, id, null, "user", id, ip,
                Map.of("lockMinutes", LOCK_MINUTES));
        }
        return Optional.empty();
    }

    /**
     * Establecimientos donde el usuario puede operar (US-05, NEX-24): afiliación activa
     * en organización activa, con sus roles. Vacía para el master (D-13: entra directo
     * al contexto de plataforma, sin establecimiento).
     */
    public List<Establishment> establishments(long userId) {
        Boolean admin = jdbc.sql("SELECT is_platform_admin FROM users WHERE id = :id")
            .param("id", userId)
            .query((rs, n) -> rs.getBoolean(1))
            .optional()
            .orElse(false);
        if (admin) {
            return List.of();
        }
        record Row(long orgId, String name, String role) { }
        List<Row> rows = jdbc.sql("""
                SELECT o.id, o.name, mr.role::text AS role
                FROM organization_members om
                JOIN organizations o ON o.id = om.organization_id AND o.is_active
                JOIN member_roles mr ON mr.member_id = om.id
                WHERE om.user_id = :id AND om.is_active
                ORDER BY o.name, mr.role
                """)
            .param("id", userId)
            .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3)))
            .list();
        Map<Long, Establishment> byOrg = new LinkedHashMap<>();
        for (Row r : rows) {
            byOrg.computeIfAbsent(r.orgId(), k -> new Establishment(k, r.name(), new ArrayList<>()))
                .roles().add(r.role());
        }
        return List.copyOf(byOrg.values());
    }

    /**
     * Valida que el usuario pueda operar en ese establecimiento (US-05, NEX-24).
     *
     * @return false si el gimnasio está inactivo o la afiliación no está vigente.
     */
    public boolean canOperateIn(long userId, long organizationId) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM organization_members om
                  JOIN organizations o ON o.id = om.organization_id AND o.is_active
                  WHERE om.user_id = :id AND om.organization_id = :org AND om.is_active)
                """)
            .param("id", userId)
            .param("org", organizationId)
            .query((rs, n) -> rs.getBoolean(1))
            .single();
    }

    /**
     * Suma un fallo y, si llega al máximo, bloquea la cuenta. Todo en un solo UPDATE atómico: leer
     * el contador y luego escribirlo perdería fallos cuando llegan intentos simultáneos. El WHERE
     * repite la condición "activa y no bloqueada" para que un intento concurrente que llegó tarde
     * no sume ni extienda un bloqueo que otro ya aplicó. Al bloquear, el contador vuelve a 0
     * para que, pasados los 15 minutos, la persona tenga de nuevo 5 intentos.
     *
     * @return true si este fallo fue el que bloqueó la cuenta
     */
    private boolean registerFailedAttempt(long userId) {
        return jdbc.sql("""
                UPDATE users
                SET failed_login_count = CASE WHEN failed_login_count + 1 >= :max
                                              THEN 0 ELSE failed_login_count + 1 END,
                    locked_until = CASE WHEN failed_login_count + 1 >= :max
                                        THEN now() + make_interval(mins => :minutes)
                                        ELSE locked_until END
                WHERE id = :id AND is_active AND (locked_until IS NULL OR locked_until <= now())
                RETURNING (locked_until IS NOT NULL AND locked_until > now()) AS just_locked
                """)
            .param("max", MAX_FAILED_ATTEMPTS)
            .param("minutes", LOCK_MINUTES)
            .param("id", userId)
            .query((rs, n) -> rs.getBoolean("just_locked"))
            .optional()
            .orElse(false);
    }

    /** Un acceso exitoso reinicia el contador (D-15: el SRS no lo define; se asume reinicio). */
    private void resetFailedAttempts(long userId) {
        jdbc.sql("""
                UPDATE users SET failed_login_count = 0, locked_until = NULL
                WHERE id = :id AND (failed_login_count <> 0 OR locked_until IS NOT NULL)
                """)
            .param("id", userId)
            .update();
    }

    /**
     * Cambia la contraseña de quien tiene la sesión abierta (US-02, RF-102): la temporal del primer
     * ingreso o una voluntaria. Siempre exige la contraseña actual (en el caso de la temporal, la temporal).
     * Guarda solo el hash, limpia la marca de cambio pendiente y su vigencia, y revoca las demás
     * sesiones del usuario; la actual se conserva. No se audita: la lista de eventos (RF-105) incluye el
     * restablecimiento asistido, no el cambio propio.
     */
    @Transactional
    public PasswordChangeResult changePassword(long userId, String current, String newPassword,
                                               String currentSessionId) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            return PasswordChangeResult.TOO_SHORT;
        }
        String storedHash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id")
            .param("id", userId).query(String.class).single();
        if (current == null || !encoder.matches(current, storedHash)) {
            return PasswordChangeResult.WRONG_CURRENT;
        }
        if (encoder.matches(newPassword, storedHash)) {
            return PasswordChangeResult.SAME_AS_CURRENT;
        }
        jdbc.sql("""
                UPDATE users
                SET password_hash = :hash, must_change_password = false, temp_password_expires_at = NULL
                WHERE id = :id
                """)
            .param("hash", encoder.encode(newPassword))
            .param("id", userId)
            .update();
        sessions.findByPrincipalName(String.valueOf(userId)).keySet().stream()
            .filter(sessionId -> !sessionId.equals(currentSessionId))
            .forEach(sessionId -> sessions.deleteById(sessionId));
        return PasswordChangeResult.CHANGED;
    }

    /**
     * Restablecimiento asistido (US-04, RF-103, RN-38): un Staff, Administrador o el
     * Administrador Master asigna una contraseña temporal a una cuenta. La temporal
     * se muestra una sola vez (en el resultado), levanta el bloqueo y revoca las
     * sesiones del titular. Queda auditado como PASSWORD_RESET.
     */
    @Transactional
    public ResetResult resetPassword(long actorId, Long activeOrgId, long targetId, String ip) {
        boolean targetExists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM users WHERE id = :id AND is_active)")
            .param("id", targetId)
            .query((rs, n) -> rs.getBoolean(1))
            .single();
        if (!targetExists) {
            return new ResetResult(ResetStatus.TARGET_NOT_FOUND, null);
        }
        boolean actorMaster = jdbc.sql("SELECT is_platform_admin FROM users WHERE id = :id")
            .param("id", actorId)
            .query((rs, n) -> rs.getBoolean(1))
            .optional()
            .orElse(false);

        Long orgForAudit;
        if (actorMaster) {
            orgForAudit = null; // evento global
        } else {
            // El filtro ya exigió establecimiento activo; org nula aquí = sin permiso.
            if (activeOrgId == null) {
                return new ResetResult(ResetStatus.FORBIDDEN, null);
            }
            List<String> roles = jdbc.sql("""
                    SELECT mr.role::text
                    FROM organization_members om
                    JOIN member_roles mr ON mr.member_id = om.id
                    WHERE om.user_id = :actor AND om.organization_id = :org AND om.is_active
                    """)
                .param("actor", actorId)
                .param("org", activeOrgId)
                .query(String.class)
                .list();
            if (!roles.contains("admin") && !roles.contains("staff")) {
                return new ResetResult(ResetStatus.FORBIDDEN, null);
            }
            if (!canOperateIn(targetId, activeOrgId)) {
                return new ResetResult(ResetStatus.TARGET_NOT_FOUND, null);
            }
            long memberships = jdbc.sql("""
                    SELECT count(*)
                    FROM organization_members om
                    JOIN organizations o ON o.id = om.organization_id AND o.is_active
                    WHERE om.user_id = :target AND om.is_active
                    """)
                .param("target", targetId)
                .query(Long.class)
                .single();
            if (memberships > 1 && hasLoggedIn(targetId)) {
                return new ResetResult(ResetStatus.MASTER_REQUIRED, null);
            }
            orgForAudit = activeOrgId;
        }

        String temporary = generateTemporaryPassword();
        jdbc.sql("""
                UPDATE users
                SET password_hash = :hash, must_change_password = true,
                    temp_password_expires_at = now() + make_interval(hours => :hours),
                    failed_login_count = 0, locked_until = NULL
                WHERE id = :id
                """)
            .param("hash", encoder.encode(temporary))
            .param("hours", TEMP_PASSWORD_HOURS)
            .param("id", targetId)
            .update();
        sessions.findByPrincipalName(String.valueOf(targetId)).keySet()
            .forEach(sessionId -> sessions.deleteById(sessionId));
        audit.record(AuditEventType.PASSWORD_RESET, actorId, orgForAudit, "user", targetId, ip, null);
        return new ResetResult(ResetStatus.OK, temporary);
    }

    /** ¿La cuenta ya tuvo un ingreso exitoso? Se sabe por su LOGIN auditado (US-01). */
    private boolean hasLoggedIn(long userId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM audit_events
                              WHERE actor_user_id = :id AND event_type = 'LOGIN')
                """)
            .param("id", userId)
            .query((rs, n) -> rs.getBoolean(1))
            .single();
    }

    /** Contraseña temporal nueva, sin tocar la BD ni auditar: la usa quien crea una cuenta (US-09). */
//public record TemporaryPassword(String plaintext, String hash) { }

public TemporaryPassword newTemporaryPassword() {
    String temporary = generateTemporaryPassword();
    return new TemporaryPassword(temporary, encoder.encode(temporary));
}

/** Revoca todas las sesiones de un usuario (al perder un rol, defensa en profundidad). */
public void revokeUserSessions(long userId) {
    sessions.findByPrincipalName(String.valueOf(userId)).keySet()
        .forEach(sessionId -> sessions.deleteById(sessionId));
}



    private String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_ALPHABET[random.nextInt(TEMP_ALPHABET.length)]);
        }
        return sb.toString();
    }

    @Transactional
    public void logout(long userId, String ip) {
        audit.record(AuditEventType.LOGOUT, userId, null, "user", userId, ip, null);
    }
}
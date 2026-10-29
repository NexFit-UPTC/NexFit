package com.nexfit.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * La sesión guarda solo el id del usuario y el establecimiento activo que eligió (US-05, NEX-24).
 * En cada solicitud se consulta en BD si sigue activo y sin bloqueo (si no, 401) y se arman sus
 * autoridades vigentes: "PLATFORM_ADMIN" para el master (D-13: sin establecimiento) o los roles
 * del establecimiento activo ("rol:orgId", RN-03) para los demás. Nada de esto se guarda en la sesión.
 *
 * <p>Sin establecimiento activo no se puede operar: cualquier ruta fuera de las permitidas
 * responde 403 con el código ESTABLISHMENT_REQUIRED para que el cliente muestre el selector.
 *
 * <p>Un establecimiento sin Administrador activo (RN-04) tampoco opera: las rutas fuera de esas
 * mismas permitidas responden 403 ESTABLISHMENT_WITHOUT_ADMIN. El master no se ve afectado.
 *
 * <p>Si el usuario tiene una contraseña temporal sin cambiar (US-02), solo puede cambiarla o cerrar
 * sesión: cualquier otra ruta recibe 403 con código PASSWORD_CHANGE_REQUIRED y no se le dan
 * autoridades. Una temporal con la vigencia vencida deja de servir (401).
 */
public class SessionUserFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "NEXFIT_USER_ID";
    /** Establecimiento activo elegido por el usuario (US-05, NEX-24). */
    public static final String ORG_ID_ATTRIBUTE = "NEXFIT_ORG_ID";

    /** Rutas permitidas mientras la contraseña temporal sigue pendiente de cambio. */
    private static final Set<String> ALLOWED_WHILE_PASSWORD_PENDING =
        Set.of("/auth/login", "/auth/logout", "/auth/password");

    /** Rutas que no exigen establecimiento activo (elegirlo es justamente su función). */
    private static final Set<String> ALLOWED_WITHOUT_ESTABLISHMENT = Set.of(
        "/auth/login", "/auth/logout", "/auth/password",
        "/auth/establishments", "/auth/establishment");

    private record Account(boolean platformAdmin, boolean mustChangePassword) { }

    private final JdbcClient jdbc;

    public SessionUserFilter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Los GET públicos de gimnasios (NEX-97) no leen la sesión: una cookie vencida o un usuario sin
     * establecimiento activo no debe afectar lo que ve un visitante. Solo GET y solo /public/gyms.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApp(request);
        return "GET".equals(request.getMethod()) && (path.equals("/public/gyms") || path.startsWith("/public/gyms/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Object id = session == null ? null : session.getAttribute(USER_ID_ATTRIBUTE);
        if (id instanceof Long userId) {
            var account = jdbc.sql("""
                    SELECT is_platform_admin, must_change_password FROM users
                    WHERE id = :id AND is_active AND (locked_until IS NULL OR locked_until <= now())
                      AND NOT (must_change_password
                               AND temp_password_expires_at IS NOT NULL
                               AND temp_password_expires_at <= now())
                    """)
                .param("id", userId)
                .query((rs, n) -> new Account(rs.getBoolean("is_platform_admin"), rs.getBoolean("must_change_password")))
                .optional();
            if (account.isEmpty()) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            List<GrantedAuthority> authorities = new ArrayList<>();
            if (account.get().mustChangePassword()) {
                if (!ALLOWED_WHILE_PASSWORD_PENDING.contains(pathWithinApp(request))) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\","
                        + "\"message\":\"Debes cambiar tu contraseña temporal antes de continuar\"}");
                    return;
                }
                SecurityContext pending = SecurityContextHolder.createEmptyContext();
                pending.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId, null, authorities));
                SecurityContextHolder.setContext(pending);
                chain.doFilter(request, response);
                return;
            }
            if (account.get().platformAdmin()) {
                // D-13: el master entra directo al contexto de plataforma, sin establecimiento.
                authorities.add(new SimpleGrantedAuthority("PLATFORM_ADMIN"));
            } else {
                Object orgAttr = session.getAttribute(ORG_ID_ATTRIBUTE);
                Long orgId = orgAttr instanceof Long l ? l : null;
                if (orgId != null && activeEstablishment(userId, orgId)) {
                    if (!ALLOWED_WITHOUT_ESTABLISHMENT.contains(pathWithinApp(request)) && !hasActiveAdmin(orgId)) {
                        // RN-04: sin Administrador asignado el establecimiento no opera (el master sí, por su rama).
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":\"ESTABLISHMENT_WITHOUT_ADMIN\","
                            + "\"message\":\"El establecimiento no tiene un Administrador asignado\"}");
                        return;
                    }
                    // Solo los roles del establecimiento activo (RN-03: la unión de sus roles).
                    jdbc.sql("""
                            SELECT mr.role::text || ':' || om.organization_id
                            FROM organization_members om
                            JOIN member_roles mr ON mr.member_id = om.id
                            WHERE om.user_id = :id AND om.organization_id = :org AND om.is_active
                            """)
                        .param("id", userId).param("org", orgId).query(String.class).list()
                        .forEach(a -> authorities.add(new SimpleGrantedAuthority(a)));
                } else if (!ALLOWED_WITHOUT_ESTABLISHMENT.contains(pathWithinApp(request))) {
                    // US-05: sin establecimiento activo no se puede operar; la sesión sigue viva.
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"ESTABLISHMENT_REQUIRED\","
                        + "\"message\":\"Elige el establecimiento con el que vas a operar\"}");
                    return;
                }
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId, null, authorities));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    /** ¿Sigue vigente la afiliación del usuario con ese establecimiento? */
    private boolean activeEstablishment(long userId, long orgId) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM organization_members om
                  JOIN organizations o ON o.id = om.organization_id AND o.is_active
                  WHERE om.user_id = :id AND om.organization_id = :org AND om.is_active)
                """)
            .param("id", userId)
            .param("org", orgId)
            .query((rs, n) -> rs.getBoolean(1))
            .single();
    }

    /** ¿Tiene el establecimiento un Administrador con afiliación y cuenta activas? (RN-04) */
    private boolean hasActiveAdmin(long orgId) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM organization_members om
                  JOIN member_roles mr ON mr.member_id = om.id AND mr.role = 'admin'
                  JOIN users u ON u.id = om.user_id AND u.is_active
                  WHERE om.organization_id = :org AND om.is_active)
                """)
            .param("org", orgId)
            .query((rs, n) -> rs.getBoolean(1))
            .single();
    }

    /** Ruta sin el context-path (/api en producción), p. ej. "/auth/password". */
    private static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }
}

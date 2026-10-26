package com.nexfit.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.annotation.Annotation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Aplica la matriz 3.7 en el servidor con default-deny: todo handler de com.nexfit debe declarar
 * {@link RequiresAccess} o {@link AnyAuthenticated}; si no, 403 (ACCESS_RULE_MISSING). Los handlers
 * de otros paquetes (error de Spring, actuator) no se tocan. Corre antes del handler, así que un
 * rechazo no produce ningún cambio. La sesión ya la exigió SecurityConfig (401).
 */
public class AccessInterceptor implements HandlerInterceptor {

    static final String PACKAGE = "com.nexfit.";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws IOException {
        if (!(handler instanceof HandlerMethod method) || !inApplication(method)) {
            return true;
        }
        RequiresAccess rule = find(method, RequiresAccess.class);
        if (rule == null) {
            if (find(method, AnyAuthenticated.class) != null) {
                return true;
            }
            return deny(response, "ACCESS_RULE_MISSING", "El endpoint no declara su regla de acceso");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!Permisos.permite(rule.funcion(), rule.accion(), auth.getAuthorities())) {
            return deny(response, "ACCESS_DENIED", "No tienes permiso para esta operación");
        }
        return true;
    }

    /** ¿El bean del handler es de la aplicación (com.nexfit)? Así no se tocan el error de Spring ni actuator. */
    static boolean inApplication(HandlerMethod method) {
        return ClassUtils.getUserClass(method.getBeanType()).getName().startsWith(PACKAGE);
    }

    /** Regla del método; si no la tiene, la de la clase. */
    static <A extends Annotation> A find(HandlerMethod method, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return onMethod != null ? onMethod
            : AnnotatedElementUtils.findMergedAnnotation(ClassUtils.getUserClass(method.getBeanType()), type);
    }

    private static boolean deny(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
        return false;
    }
}

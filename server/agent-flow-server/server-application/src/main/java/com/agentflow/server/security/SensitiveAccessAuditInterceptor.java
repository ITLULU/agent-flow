package com.agentflow.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 敏感端点访问审计：只记录"谁在何时访问了哪条路由"，不记录请求体、查询串或密钥值。
 * 凭证接口不把明文/密文回给 API，但"谁读了凭证元数据/改了密钥"仍需可追溯。
 */
@Component
public class SensitiveAccessAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SensitiveAccessAuditInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || EndpointSensitivityCatalog.resolve(handlerMethod) != Sensitivity.SENSITIVE) {
            return true;
        }
        Principal principal = PrincipalContext.peek();
        log.info("敏感端点访问 uid={} role={} {} {}", principal == null ? "-" : principal.uid(),
                principal == null ? "-" : principal.role(), request.getMethod(), request.getRequestURI());
        return true;
    }
}

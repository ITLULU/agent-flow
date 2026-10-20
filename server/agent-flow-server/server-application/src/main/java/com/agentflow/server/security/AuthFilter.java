package com.agentflow.server.security;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 鉴权过滤器（P3 替换 P0 骨架的 ApiTokenFilter，链路位置不变）：
 * - enabled=false：一律以系统匿名主体（uid=0, admin）放行——本地零配置开发口径，兼容存量 e2e；
 * - enabled=true：白名单（健康探针/注册登录）直通，其余需 `Authorization: Bearer <JWT>` 或服务通道 X-API-Token；
 * - 主体统一放入 {@link PrincipalContext}，finally 清理（ThreadLocal 不跨请求泄漏）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@EnableConfigurationProperties(SecurityProperties.class)
public class AuthFilter extends OncePerRequestFilter {

    private final SecurityProperties props;
    private final JwtCodec jwtCodec;
    private final ObjectMapper objectMapper;

    public AuthFilter(SecurityProperties props, JwtCodec jwtCodec, ObjectMapper objectMapper) {
        this.props = props;
        this.jwtCodec = jwtCodec;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Principal principal;
        if (!props.enabled()) {
            principal = new Principal(Principal.SYSTEM_UID, "system", Principal.ROLE_ADMIN);
        } else if (isWhitelisted(request.getRequestURI())) {
            principal = new Principal(Principal.SYSTEM_UID, "anonymous", Principal.ROLE_ADMIN);
        } else {
            principal = authenticate(request);
            if (principal == null) {
                reject(response);
                return;
            }
        }
        PrincipalContext.set(principal);
        try {
            chain.doFilter(request, response);
        } finally {
            PrincipalContext.clear();
        }
    }

    /** 返回 null 表示鉴权失败；服务通道与 JWT 双入口，比较都走恒时路径。 */
    private Principal authenticate(HttpServletRequest request) {
        String apiToken = request.getHeader("X-API-Token");
        if (!props.apiToken().isBlank() && apiToken != null
                && MessageDigest.isEqual(props.apiToken().getBytes(StandardCharsets.UTF_8),
                        apiToken.getBytes(StandardCharsets.UTF_8))) {
            return new Principal(Principal.SYSTEM_UID, "service", "ops");
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                return jwtCodec.verify(auth.substring(7).trim());
            } catch (com.agentflow.common.error.ApiException e) {
                return null; // 细节只进日志由上层需要时补，响应统一 401 不泄露原因
            }
        }
        return null;
    }

    private boolean isWhitelisted(String uri) {
        return uri.startsWith("/actuator/health") || uri.startsWith("/api/auth/");
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail(ErrorCode.UNAUTHORIZED, "缺少或错误的 Authorization: Bearer <token>"));
    }
}

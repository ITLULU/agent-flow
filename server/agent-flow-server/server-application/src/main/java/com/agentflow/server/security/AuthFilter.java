package com.agentflow.server.security;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
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

    /** 免鉴权入口精确清单（新增公开端点必须在这里显式登记，不允许扩前缀）。 */
    private static final Set<String> AUTH_WHITELIST = Set.of("/api/auth/register", "/api/auth/login");
    private static final String METRICS_PATH = "/actuator/prometheus";

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
        String path = normalizedPath(request);
        Principal principal;
        if (!props.enabled()) {
            principal = new Principal(Principal.SYSTEM_UID, "system", Principal.ROLE_ADMIN);
        } else if (isHealth(path)) {
            // 健康探针只读且 show-details=never，保持匿名；比的是归一化精确段，挡住 /actuator/health/../prometheus
            principal = new Principal(Principal.SYSTEM_UID, "anonymous", Principal.ROLE_ANONYMOUS);
        } else if (METRICS_PATH.equals(path) && hasMetricsToken(request)) {
            // 抓取令牌只在此端点被认可：同一把 token 拿去调业务 API 会在下面 authenticate 中失效
            principal = new Principal(Principal.SYSTEM_UID, "metrics", Principal.ROLE_METRICS);
        } else if (isStaticAsset(request, path)) {
            // 前端登录页必须先于登录可达；只放行构建产物与只读 GET，/api/** 不受影响
            principal = new Principal(Principal.SYSTEM_UID, "anonymous", Principal.ROLE_ANONYMOUS);
        } else if (AUTH_WHITELIST.contains(path)) {
            // 白名单只有注册/登录，它们不需要管理员；给匿名角色，避免前缀匹配被拿去当跳板
            principal = new Principal(Principal.SYSTEM_UID, "anonymous", Principal.ROLE_ANONYMOUS);
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

    /**
     * 白名单用"归一化后的精确路径"而非原始 URI 前缀：路由层会规范化 ../ 与 %-编码，前缀匹配会让
     * `/api/auth/../workflows` 这类路径在过滤器上当匿名放行、到 Spring 却命中受保护的业务端点。
     */
    private static String normalizedPath(HttpServletRequest request) {
        try {
            return URI.create(request.getRequestURI()).normalize().getPath();
        } catch (IllegalArgumentException e) {
            return null; // 非法 URI 一律走鉴权，不在这里猜
        }
    }

    private static boolean isHealth(String path) {
        return path != null && (path.equals("/actuator/health") || path.startsWith("/actuator/health/"));
    }

    /** 静态前端只允许只读方法；路径再归一化，避免 /assets/../api/... 借静态前缀穿透。 */
    private static boolean isStaticAsset(HttpServletRequest request, String path) {
        if (path == null || !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
            return false;
        }
        return path.equals("/") || path.equals("/index.html") || path.equals("/favicon.svg")
                || path.startsWith("/assets/");
    }

    /** Prometheus 抓取身份：配置了 AGENTFLOW_METRICS_TOKEN 才接受，比较走恒时路径，空配置永不命中。 */
    private boolean hasMetricsToken(HttpServletRequest request) {
        String expected = props.metricsToken();
        if (expected.isBlank()) {
            return false;
        }
        String presented = null;
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            presented = auth.substring(7).trim();
        }
        if (presented == null) {
            presented = request.getHeader("X-Metrics-Token");
        }
        return presented != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail(ErrorCode.UNAUTHORIZED, "缺少或错误的 Authorization: Bearer <token>"));
    }
}

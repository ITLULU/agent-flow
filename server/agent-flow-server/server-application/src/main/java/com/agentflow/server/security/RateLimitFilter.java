package com.agentflow.server.security;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每分钟固定窗限流（A0429 + Retry-After），按"身份 + 用途"分桶：
 * - 登录用户：per-uid，阈值 rate-limit-per-minute；
 * - 服务通道（X-API-Token）：独立 svc 桶，同上阈值；
 * - /api/auth/**（注册/登录）：per-客户端 IP，独立阈值 rate-limit-auth-per-minute，不与业务桶互相挤占。
 * 只在 enabled=true 生效——本地零配置开发不设闸；rate-limit-per-minute=0 是整套限流的总开关（关闭）。
 * 内存态单实例口径：多实例需网关/Redis 计数（容量阶段兑现，已记台账）。
 * 为什么拆桶（评审 P6 #6）：原先匿名与服务通道同为 uid=0，共用一个桶——
 * 任何人刷 `/api/auth/login` 就能把 120/min 打满，**运维脚本和所有真实用户一起被拒**，
 * 拒绝服务不需要泄露任何凭据即达成；同时公开登录也被全局压到 2 rps。
 * 注：AuthFilter（order +10）在本过滤器（+20）之前，匿名访问业务 API 早在 401 就被挡掉，
 * 所以这里不需要"匿名业务桶"分支。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@EnableConfigurationProperties(SecurityProperties.class)
public class RateLimitFilter extends OncePerRequestFilter {

    private final SecurityProperties props;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(SecurityProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    private static final class Window {
        final long minute;
        int count;

        Window(long minute, int count) {
            this.minute = minute;
            this.count = count;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.enabled() || props.rateLimitPerMinute() == 0 || !request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        Principal principal = PrincipalContext.get();
        boolean authEndpoint = request.getRequestURI().startsWith("/api/auth/");
        Bucket bucket = authEndpoint
                ? new Bucket("ip:" + clientIp(request) + ":auth", props.rateLimitAuthPerMinute())
                : bucketFor(principal);
        if (bucket.limit() == 0) { // 该桶显式不设上限
            chain.doFilter(request, response);
            return;
        }
        long minute = System.currentTimeMillis() / 60_000;
        // computeIfAbsent + merge：同窗累加，跨窗重置计数（不能写成 C 逗号表达式）
        Window window = windows.merge(bucket.key() + ":" + minute, new Window(minute, 1),
                (oldv, fresh) -> {
                    oldv.count++;
                    return oldv;
                });
        boolean limited = window.count > bucket.limit();
        if (windows.size() > 10_000) { // 简单防泄漏：桶键含分钟，直接清掉非当前窗
            windows.entrySet().removeIf(e -> e.getValue().minute != minute);
        }
        if (limited) {
            long retryAfter = 60 - (System.currentTimeMillis() / 1000) % 60;
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.fail(ErrorCode.RATE_LIMITED,
                            "超过每分钟 " + bucket.limit() + " 次请求上限，请 " + retryAfter + "s 后重试"));
            return;
        }
        chain.doFilter(request, response);
    }

    private Bucket bucketFor(Principal principal) {
        // 能走到这里的只有三种：登录用户、服务通道、白名单里的 /api/auth/**（AuthFilter 在前，匿名业务请求已被 401 挡掉）
        // ⇒ 服务通道必须单独立桶，否则任何人刷登录接口就能把它和业务桶一起打满（评审 P6 #6 的实际形态）
        if (Principal.ROLE_OPS.equals(principal.role())) {
            return new Bucket("svc", props.rateLimitPerMinute());
        }
        return new Bucket("u:" + principal.uid(), props.rateLimitPerMinute());
    }

    /** 客户端 IP：默认只信连接地址；打开 trust-forwarded-for 才取 XFF 第一段（服务前面必须有一层会覆盖该头的可信反代）。 */
    private String clientIp(HttpServletRequest request) {
        if (props.trustForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private record Bucket(String key, int limit) {
    }
}

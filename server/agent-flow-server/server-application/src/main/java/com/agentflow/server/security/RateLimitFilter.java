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
 * per-principal 每分钟固定窗限流（A0429 + Retry-After）。
 * 只在 enabled=true 生效——本地零配置开发不设闸；0=显式关闭。
 * 内存态单实例口径：多实例需网关/Redis 计数（容量阶段兑现，已记台账）。
 * 匿名/白名单请求共享 uid=0 桶——登录爆破天然被同一窗口约束。
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
        long uid = PrincipalContext.get().uid();
        long minute = System.currentTimeMillis() / 60_000;
        // computeIfAbsent + merge：同窗累加，跨窗重置计数（不能写成 C 逗号表达式）
        Window window = windows.merge(uid + ":" + minute, new Window(minute, 1),
                (oldv, fresh) -> {
                    oldv.count++;
                    return oldv;
                });
        boolean limited = window.count > props.rateLimitPerMinute();
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
                            "超过每分钟 " + props.rateLimitPerMinute() + " 次请求上限，请 " + retryAfter + "s 后重试"));
            return;
        }
        chain.doFilter(request, response);
    }
}

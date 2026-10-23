package com.agentflow.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * AuthFilter 分支矩阵：关闭态匿名放行 / 开启态 Bearer、服务通道、白名单、拒绝路径。
 */
class AuthFilterTest {

    private static final byte[] SECRET = "filter-test-secret-at-least-32-chars!!".getBytes(StandardCharsets.UTF_8);

    private final JwtCodec codec = new JwtCodec(SECRET, new ObjectMapper(), Duration.ofHours(2));

    private SecurityProperties props(boolean enabled) {
        return props(enabled, "metrics-token-1234567890");
    }

    private SecurityProperties props(boolean enabled, String metricsToken) {
        return new SecurityProperties(enabled, "svc-token", "filter-test-secret-at-least-32-chars!!",
                Duration.ofHours(2), 120, 30, false, "", "", "", "", metricsToken);
    }

    private MockHttpServletResponse run(AuthFilter filter, String uri, String authHeader, String svcHeader,
                                        AtomicReference<Principal> seen) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        if (authHeader != null) {
            req.addHeader("Authorization", authHeader);
        }
        if (svcHeader != null) {
            req.addHeader("X-API-Token", svcHeader);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response)
                    throws java.io.IOException, ServletException {
                if (seen != null) {
                    seen.set(PrincipalContext.get()); // 链内必须已可见主体
                }
                super.doFilter(request, response);
            }
        };
        filter.doFilter(req, resp, chain);
        return resp;
    }

    @Test
    void disabledModePassesThroughAsSystemAdmin() throws Exception {
        AuthFilter filter = new AuthFilter(props(false), codec, new ObjectMapper());
        AtomicReference<Principal> seen = new AtomicReference<>();
        MockHttpServletResponse resp = run(filter, "/api/workflows/1", null, null, seen);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(seen.get().uid()).isEqualTo(Principal.SYSTEM_UID);
        assertThat(seen.get().isAdmin()).isTrue();
    }

    @Test
    void enabledModeAcceptsBearerToken() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        AtomicReference<Principal> seen = new AtomicReference<>();
        String token = codec.issue(7L, "alice", "user");
        MockHttpServletResponse resp = run(filter, "/api/workflows/1", "Bearer " + token, null, seen);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(seen.get().uid()).isEqualTo(7L);
        assertThat(seen.get().username()).isEqualTo("alice");
    }

    @Test
    void enabledModeAcceptsServiceToken() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        AtomicReference<Principal> seen = new AtomicReference<>();
        MockHttpServletResponse resp = run(filter, "/api/workflows/1", null, "svc-token", seen);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(seen.get().username()).isEqualTo("service");
        // 评审 P6 #2：一把静态 token 不得等于全站管理员——它只能碰自己（uid=0）名下的资源
        assertThat(seen.get().isAdmin()).isFalse();
        assertThat(seen.get().canAccess(Principal.SYSTEM_UID)).isTrue();
        assertThat(seen.get().canAccess(42L)).isFalse();
    }

    @Test
    void whitelistPathsDoNotGrantAdmin() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        AtomicReference<Principal> seen = new AtomicReference<>();
        assertThat(run(filter, "/api/auth/login", null, null, seen).getStatus()).isEqualTo(200);
        assertThat(seen.get().isAdmin()).isFalse();
        // 前缀白名单会被 /api/auth/../workflows 这类路径拿去当跳板：归一化后必须是精确清单里的路径才放行
        MockHttpServletResponse traversal = run(filter, "/api/auth/../workflows/1", null, null, null);
        assertThat(traversal.getStatus()).isEqualTo(401);
    }

    @Test
    void enabledModeWhitelistsAuthAndHealth() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        assertThat(run(filter, "/api/auth/login", null, null, null).getStatus()).isEqualTo(200);
        assertThat(run(filter, "/api/auth/register", null, null, null).getStatus()).isEqualTo(200);
        assertThat(run(filter, "/actuator/health/liveness", null, null, null).getStatus()).isEqualTo(200);
    }

    @Test
    void enabledModeRejectsMissingOrBadToken() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        MockHttpServletResponse missing = run(filter, "/api/workflows/1", null, null, null);
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getContentAsString()).contains("A0401");
        MockHttpServletResponse bad = run(filter, "/api/workflows/1", "Bearer garbage.token.here", null, null);
        assertThat(bad.getStatus()).isEqualTo(401);
        MockHttpServletResponse wrongSvc = run(filter, "/api/workflows/1", null, "wrong-token", null);
        assertThat(wrongSvc.getStatus()).isEqualTo(401);
    }

    @Test
    void metricsTokenUnlocksOnlyPrometheus() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        AtomicReference<Principal> seen = new AtomicReference<>();
        assertThat(run(filter, "/actuator/prometheus", "Bearer metrics-token-1234567890", null, seen).getStatus())
                .isEqualTo(200);
        assertThat(seen.get().role()).isEqualTo(Principal.ROLE_METRICS);
        assertThat(seen.get().isAdmin()).isFalse();
        // 抓取器常用自定义头（或 Authorization），两种入口都要能用，但不能拿同一把 token 调业务接口
        assertThat(run(filter, "/actuator/prometheus", null, null, null).getStatus()).isEqualTo(401);
        assertThat(run(filter, "/actuator/prometheus", "Bearer wrong-metrics-token", null, null).getStatus())
                .isEqualTo(401);
        assertThat(run(filter, "/api/workflows/1", "Bearer metrics-token-1234567890", null, null).getStatus())
                .isEqualTo(401);
    }

    @Test
    void metricsTokenCanComeFromDedicatedHeader() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/prometheus");
        req.addHeader("X-Metrics-Token", "metrics-token-1234567890");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void prometheusStaysLockedWhenNoMetricsTokenConfigured() throws Exception {
        AuthFilter filter = new AuthFilter(props(true, ""), codec, new ObjectMapper());
        assertThat(run(filter, "/actuator/prometheus", "Bearer metrics-token-1234567890", null, null).getStatus())
                .isEqualTo(401);
        assertThat(run(filter, "/actuator/prometheus", null, null, null).getStatus()).isEqualTo(401);
    }

    @Test
    void healthPrefixCannotBeUsedToReachPrometheus() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        assertThat(run(filter, "/actuator/health", null, null, null).getStatus()).isEqualTo(200);
        assertThat(run(filter, "/actuator/health/liveness", null, null, null).getStatus()).isEqualTo(200);
        // 原始 URI 前缀曾把 /actuator/health/../prometheus 当健康探针放行；归一化后必须回到鉴权路径
        assertThat(run(filter, "/actuator/health/../prometheus", null, null, null).getStatus()).isEqualTo(401);
    }

    @Test
    void staticFrontendIsPublicButOnlyForReads() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        assertThat(run(filter, "/", null, null, null).getStatus()).isEqualTo(200);
        assertThat(run(filter, "/index.html", null, null, null).getStatus()).isEqualTo(200);
        assertThat(run(filter, "/assets/index-abc123.js", null, null, null).getStatus()).isEqualTo(200);
        // 静态白名单只对 GET/HEAD 生效，不能拿来当写接口的旁路
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/assets/x.js");
        MockHttpServletResponse postResp = new MockHttpServletResponse();
        filter.doFilter(post, postResp, new MockFilterChain());
        assertThat(postResp.getStatus()).isEqualTo(401);
        // 静态目录穿越归一化后落到业务路径，必须回到鉴权
        assertThat(run(filter, "/assets/../api/workflows/1", null, null, null).getStatus()).isEqualTo(401);
    }

    @Test
    void principalContextClearedAfterRequest() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        run(filter, "/api/workflows/1", "Bearer " + codec.issue(7L, "a", "user"), null, null);
        // filter finally 清理后，线程复用场景不得残留上一请求主体
        try {
            PrincipalContext.get();
            throw new AssertionError("PrincipalContext 未清理");
        } catch (com.agentflow.common.error.ApiException expected) {
            // 预期：读不到主体
        }
    }
}

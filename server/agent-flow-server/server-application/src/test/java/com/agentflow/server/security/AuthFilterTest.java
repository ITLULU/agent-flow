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
        return new SecurityProperties(enabled, "svc-token", "filter-test-secret-at-least-32-chars!!",
                Duration.ofHours(2), 120, "");
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
    }

    @Test
    void enabledModeWhitelistsAuthAndHealth() throws Exception {
        AuthFilter filter = new AuthFilter(props(true), codec, new ObjectMapper());
        assertThat(run(filter, "/api/auth/login", null, null, null).getStatus()).isEqualTo(200);
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

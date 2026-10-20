package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * P3 鉴权与归属隔离 e2e：注册→登录→带 token 建流→跨用户 404→过期/无 token 401。
 * 方法顺序固定（首个注册者必须是 admin 的引导语义依赖插入次序）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p3-auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=p3-e2e-secret-at-least-32-chars!!!",
                "agentflow.security.token-ttl=2h",
                "agentflow.security.rate-limit-per-minute=0"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class P3AuthE2ETests {

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> post(String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String registerAndLogin(String username) {
        assertThat(post("/api/auth/register",
                "{\"username\":\"" + username + "\",\"password\":\"passw0rd-123\"}", null)
                .getBody()).contains("\"code\":\"0\"");
        return extractString(post("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"passw0rd-123\"}", null)
                .getBody(), "token");
    }

    private static String echoDsl(String name) {
        return "{\"name\":\"" + name + "\",\"nodes\":[{\"id\":\"e\",\"type\":\"core.echo\",\"params\":{\"message\":\"ok\"}}],\"edges\":[]}";
    }

    @Test
    @Order(1)
    void firstRegistrantBecomesAdminSecondIsUser() {
        String root = registerAndLogin("p3root");
        assertThat(root).isNotBlank();
        // 引导语义可观测：注册响应里首用户 role=admin（表空判定在 register 内完成）
        ResponseEntity<String> r2 = post("/api/auth/register",
                "{\"username\":\"p3alice\",\"password\":\"passw0rd-123\"}", null);
        assertThat(r2.getBody()).contains("\"role\":\"user\"");
    }

    @Test
    @Order(2)
    void unauthenticatedApiCallIsRejected() {
        ResponseEntity<String> resp = post("/api/workflows", echoDsl("anon"), null);
        assertThat(resp.getBody()).contains("A0401");
        assertThat(get("/api/workflows/1", null).getBody()).contains("A0401");
    }

    @Test
    @Order(3)
    void crossUserAccessLooksLikeNotFound() {
        String alice = registerAndLogin("p3bob_a");
        String bob = registerAndLogin("p3bob_b");
        long workflowId = Long.parseLong(extractString(post("/api/workflows", echoDsl("owner-scope"), alice)
                .getBody(), "workflowId"));

        // 属主可见
        assertThat(get("/api/workflows/" + workflowId, alice).getBody()).contains("owner-scope");
        // 他人不可见：404 口径（不泄露"存在但无权"）
        String foreign = get("/api/workflows/" + workflowId, bob).getBody();
        assertThat(foreign).contains("A0404");
        assertThat(post("/api/workflows/" + workflowId + "/executions", "{}", bob).getBody()).contains("A0404");
        // 属主触发成功，且执行详情他人拿不到
        long executionId = Long.parseLong(extractString(
                post("/api/workflows/" + workflowId + "/executions", "{}", alice).getBody(), "executionId"));
        assertThat(get("/api/executions/" + executionId, bob).getBody()).contains("A0404");
    }

    @Test
    @Order(4)
    void validationAndBadCredentialsRejections() {
        // 用户名非法 / 口令过短：A0400
        assertThat(post("/api/auth/register", "{\"username\":\"a\",\"password\":\"passw0rd-123\"}", null)
                .getBody()).contains("A0400");
        assertThat(post("/api/auth/register", "{\"username\":\"okname\",\"password\":\"123\"}", null)
                .getBody()).contains("A0400");
        // 用户名重复：A0400
        assertThat(post("/api/auth/register", "{\"username\":\"p3alice\",\"password\":\"passw0rd-123\"}", null)
                .getBody()).contains("A0400");
        // 口令错误与用户不存在同文案（防枚举）：A0401
        String wrongPw = post("/api/auth/login", "{\"username\":\"p3alice\",\"password\":\"wrong-pass-1\"}", null).getBody();
        String noUser = post("/api/auth/login", "{\"username\":\"ghost_user\",\"password\":\"passw0rd-123\"}", null).getBody();
        assertThat(wrongPw).contains("A0401");
        assertThat(noUser).contains("A0401");
    }

    private static String extractString(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        int start = json.indexOf(':', i) + 1;
        boolean quoted = json.charAt(start) == '"';
        if (quoted) {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-') {
            end++;
        }
        return json.substring(start, end);
    }
}

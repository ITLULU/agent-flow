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
 * P7-2a 管理员引导 e2e：admin 只由 bootstrap 配置产生；注册接口即便先跑也拿不到 admin；
 * 反向对照——普通用户读他人工作流仍是 404，说明去特权没把 admin 通道一起削掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p7-boot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=p7-e2e-secret-at-least-32-chars!!!",
                "agentflow.security.bootstrap-admin-username=p7ops",
                "agentflow.security.bootstrap-admin-password=Boot-pass-1234",
                "agentflow.security.rate-limit-per-minute=0"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class P7AdminBootstrapE2ETests {

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

    private String login(String username, String password) {
        return extractString(post("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null)
                .getBody(), "token");
    }

    private String registerAndLogin(String username) {
        post("/api/auth/register", "{\"username\":\"" + username + "\",\"password\":\"passw0rd-123\"}", null);
        return login(username, "passw0rd-123");
    }

    private static String echoDsl(String name) {
        return "{\"name\":\"" + name + "\",\"nodes\":[{\"id\":\"e\",\"type\":\"core.echo\",\"params\":{\"message\":\"ok\"}}],\"edges\":[]}";
    }

    @Test
    @Order(1)
    void bootstrapAccountFromConfigIsTheAdmin() {
        ResponseEntity<String> resp = post("/api/auth/login",
                "{\"username\":\"p7ops\",\"password\":\"Boot-pass-1234\"}", null);
        assertThat(resp.getBody()).contains("\"code\":\"0\"");
        assertThat(resp.getBody()).contains("\"role\":\"admin\"");
        assertThat(extractString(resp.getBody(), "token")).isNotBlank();
    }

    @Test
    @Order(2)
    void registrationStillNeverGrantsAdmin() {
        assertThat(post("/api/auth/register",
                "{\"username\":\"p7alice\",\"password\":\"passw0rd-123\"}", null).getBody())
                .contains("\"role\":\"user\"");
        assertThat(login("p7alice", "passw0rd-123")).isNotBlank();
        // 口令不合规则直接 A0400，不给"短口令的管理员"留口子
        assertThat(post("/api/auth/register",
                "{\"username\":\"p7short\",\"password\":\"123\"}", null).getBody()).contains("A0400");
    }

    @Test
    @Order(3)
    void adminReadsForeignWorkflowWhileOtherUsersStillGetNotFound() {
        String alice = registerAndLogin("p7carol");
        long workflowId = Long.parseLong(extractString(
                post("/api/workflows", echoDsl("p7-boot-scope"), alice).getBody(), "workflowId"));

        String adminToken = login("p7ops", "Boot-pass-1234");
        assertThat(get("/api/workflows/" + workflowId, adminToken).getBody()).contains("p7-boot-scope");

        String outsider = registerAndLogin("p7dave");
        assertThat(get("/api/workflows/" + workflowId, outsider).getBody()).contains("A0404");
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

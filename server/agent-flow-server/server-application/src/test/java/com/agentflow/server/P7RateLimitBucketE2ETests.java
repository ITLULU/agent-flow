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
import org.springframework.http.ResponseEntity;

/**
 * P7-2d 评审 #6：限流分桶。
 * 修复前匿名（/api/auth/**）与服务通道同为 uid=0 ⇒ 一个桶：刷登录接口就能把 120/min 打满，
 * 运维脚本与所有真实用户一起被拒——拒绝服务不需要泄露任何凭据即达成。
 * 本类只测这一件事，阈值刻意配小（auth=2 / 业务=4）便于断言。
 * 每个用例用独立 IP，避免同窗串味；svc 桶只允许第二个用例碰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p7-rlbucket;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=p7-rl-secret-at-least-32-chars!!!",
                "agentflow.security.api-token=p7-rl-svc-token",
                "agentflow.security.rate-limit-per-minute=4",
                "agentflow.security.rate-limit-auth-per-minute=2",
                "agentflow.security.trust-forwarded-for=true",
                "agentflow.security.bootstrap-admin-username=",
                "agentflow.security.bootstrap-admin-password="})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class P7RateLimitBucketE2ETests {

    @Autowired
    TestRestTemplate rest;

    @Test
    @Order(1)
    void authBucketIsPerClientIp() {
        assertThat(login("10.7.0.1").getStatusCode().value()).isEqualTo(401);
        assertThat(login("10.7.0.1").getStatusCode().value()).isEqualTo(401);
        // 第 3 次：该 IP 的 auth 桶（上限 2）已满
        ResponseEntity<String> third = login("10.7.0.1");
        assertThat(third.getStatusCode().value()).isEqualTo(429);
        assertThat(third.getBody()).contains("A0429");
        // 反向验证"按 IP 分桶"真的生效：换个来源 IP 立刻可用，而不是全局一起瘫
        assertThat(login("10.7.0.2").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void saturatedAuthBucketDoesNotStarveServiceChannel() {
        // 先把 auth 桶打满（独立 IP），服务通道随后仍应能用完自己那 4 次额度
        login("10.7.1.1");
        login("10.7.1.1");
        assertThat(login("10.7.1.1").getStatusCode().value()).isEqualTo(429);

        for (int i = 1; i <= 4; i++) {
            ResponseEntity<String> ok = rest.exchange("/api/ping", HttpMethod.GET, svc(), String.class);
            assertThat(ok.getStatusCode().is2xxSuccessful())
                    .as("服务通道第 %d 次不该被匿名登录流量挤掉", i)
                    .isTrue();
        }
        // 额度用尽后限流照常生效（不是"svc 桶被跳过"造成的假绿）
        assertThat(rest.exchange("/api/ping", HttpMethod.GET, svc(), String.class).getStatusCode().value())
                .isEqualTo(429);
    }

    private ResponseEntity<String> login(String clientIp) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        headers.set("Content-Type", "application/json");
        return rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"whoever\",\"password\":\"wrong-password\"}", headers),
                String.class);
    }

    private HttpEntity<Void> svc() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Token", "p7-rl-svc-token");
        return new HttpEntity<>(headers);
    }
}

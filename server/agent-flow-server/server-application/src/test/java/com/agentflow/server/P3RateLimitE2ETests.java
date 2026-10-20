package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * per-principal 每分钟固定窗限流：第 4 次起 429 + A0429 + Retry-After 头。
 * 用服务通道（X-API-Token）认证，桶键 uid=0；上限配 3 便于断言。单方法防跨请求分钟边界抖动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p3-rl;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=p3-rl-secret-at-least-32-chars!!!",
                "agentflow.security.api-token=rl-svc-token",
                "agentflow.security.rate-limit-per-minute=3"})
class P3RateLimitE2ETests {

    @Autowired
    TestRestTemplate rest;

    @Test
    void fourthRequestInSameMinuteGets429() {
        HttpEntity<Void> req = new HttpEntity<>(headersWithToken());
        ResponseEntity<String> first = rest.exchange("/api/ping", HttpMethod.GET, req, String.class);
        ResponseEntity<String> second = rest.exchange("/api/ping", HttpMethod.GET, req, String.class);
        ResponseEntity<String> third = rest.exchange("/api/ping", HttpMethod.GET, req, String.class);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(third.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> fourth = rest.exchange("/api/ping", HttpMethod.GET, req, String.class);
        assertThat(fourth.getStatusCode().value()).isEqualTo(429);
        assertThat(fourth.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(fourth.getBody()).contains("A0429");
    }

    private org.springframework.http.HttpHeaders headersWithToken() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-API-Token", "rl-svc-token");
        return headers;
    }
}

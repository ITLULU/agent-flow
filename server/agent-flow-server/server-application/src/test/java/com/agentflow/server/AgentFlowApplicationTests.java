package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:af-base-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class AgentFlowApplicationTests {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthLivenessIsUp() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/liveness", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("UP");
    }

    @Test
    void pingReturnsStructuredBodyAndTraceIdHeader() {
        ResponseEntity<String> resp = rest.getForEntity("/api/ping", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"code\":\"0\"").contains("pong");
        assertThat(resp.getHeaders().getFirst("X-Trace-Id")).hasSize(32);
    }

    @Test
    void incomingValidTraceIdIsEchoedBack() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "deadbeef01");
        ResponseEntity<String> resp =
                rest.exchange("/api/ping", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getHeaders().getFirst("X-Trace-Id")).isEqualTo("deadbeef01");
        assertThat(resp.getBody()).contains("deadbeef01"); // traceId 同步进响应体，可与日志互查
    }

    @Test
    void unknownPathReturnsStructured404WithoutStacktrace() {
        ResponseEntity<String> resp = rest.getForEntity("/api/nope", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("A0404").doesNotContain("Exception");
    }
}

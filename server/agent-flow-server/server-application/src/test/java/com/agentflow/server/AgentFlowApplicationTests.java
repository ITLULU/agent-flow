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

    @Test
    void unsupportedHttpMethodReturns405NotInternalError() {
        ResponseEntity<String> resp = rest.exchange("/api/credentials", HttpMethod.POST,
                new HttpEntity<>("{}", jsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(resp.getBody()).contains("A0405").doesNotContain("B0001");
    }

    @Test
    void malformedJsonBodyReturns400NotInternalError() {
        ResponseEntity<String> resp = rest.exchange("/api/workflows", HttpMethod.POST,
                new HttpEntity<>("{\"name\":", jsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("A0400").doesNotContain("B0001");
    }

    @Test
    void schedulePutWithoutEnabledFieldStillRegistersCron() {
        long workflowId = createEchoWorkflow();
        ResponseEntity<String> resp = rest.exchange("/api/workflows/" + workflowId + "/schedule",
                HttpMethod.PUT, new HttpEntity<>("{\"cron\":\"0 0/5 * * * *\"}", jsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 旧行为：缺 enabled 被当成 false → 登记请求静默变成撤销，200 却什么都没排上
        assertThat(resp.getBody()).doesNotContain("\"nextFireAt\":\"\"");
    }

    @Test
    void scheduleEnabledWithoutCronReturnsBadRequest() {
        long workflowId = createEchoWorkflow();
        ResponseEntity<String> resp = rest.exchange("/api/workflows/" + workflowId + "/schedule",
                HttpMethod.PUT, new HttpEntity<>("{\"enabled\":true}", jsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("A0400").doesNotContain("B0001");
    }

    private long createEchoWorkflow() {
        ResponseEntity<String> created = rest.exchange("/api/workflows", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"基准流程\","
                        + "\"nodes\":[{\"id\":\"e\",\"type\":\"core.echo\",\"params\":{}}],\"edges\":[]}",
                        jsonHeaders()), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Long.parseLong(created.getBody().replaceAll("(?s).*\"workflowId\":(\\d+).*", "$1"));
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }
}

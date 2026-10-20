package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * P2 空跑 e2e（无需任何外部凭证/网络）：验证引擎语义 × P2 节点契约在真实 Spring 上下文里咬合——
 * 配置缺失不可重试（attempts=1），外部连接拒绝重试耗尽（attempts=3）；
 * 两者都因节点带 degrade 路径而降级续跑：执行终态 SUCCESS_WITH_DEGRADED，DEGRADED 标志与原因必须可见。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p2-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.nodes.llm.api-key=",
                "agentflow.nodes.image.api-key=",
                "agentflow.nodes.wechat.app-id=",
                "agentflow.nodes.wechat.app-secret="})
class P2DryRunE2ETests {

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void llmChatWithoutKeyFailsFastWithSingleAttempt() throws Exception {
        String dsl = """
                {
                  "name": "无key空跑",
                  "nodes": [{"id": "g", "type": "llm.chat", "params": {"prompt": "写一段导语"}}],
                  "edges": []
                }
                """;
        long workflowId = extractLong(postJson("/api/workflows", dsl).getBody(), "workflowId");
        String detail = executeAndPoll(workflowId, 5);

        assertThat(detail).contains("SUCCESS_WITH_DEGRADED"); // llm.chat 带降级路径：空 content 续跑
        assertThat(detail).contains("BAILIAN_API_KEY");        // 配置错误原因可见
        assertThat(detail).contains("\"attempts\":1");         // A0400 不可重试：只跑一次
        assertThat(detail).contains("DEGRADED");
    }

    @Test
    void unreachableCollectRetriesThreeAttemptsThenDegrades() throws Exception {
        String deadUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadUrl = "http://127.0.0.1:" + socket.getLocalPort() + "/";
        }
        String dsl = """
                {
                  "name": "死链采集",
                  "nodes": [{"id": "c", "type": "http.collect", "params": {"url": "%s"}}],
                  "edges": []
                }
                """.formatted(deadUrl);
        long workflowId = extractLong(postJson("/api/workflows", dsl).getBody(), "workflowId");
        String detail = executeAndPoll(workflowId, 10); // 2 次退避(1s+2s) 需更宽窗口

        assertThat(detail).contains("SUCCESS_WITH_DEGRADED"); // 采集节点 degrade：跳过该来源续跑
        assertThat(detail).contains("http.collect");
        assertThat(detail).contains("\"attempts\":3");        // 1 + 2 次重试全部用完仍失败 → 走降级
    }

    @Test
    void degradedChainStillSucceedsWithVisibleFlag() throws Exception {
        // 死链采集 + echo 续跑：degrade 生效 → 节点 DEGRADED、链路 SUCCESS、标志可见
        String deadUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadUrl = "http://127.0.0.1:" + socket.getLocalPort() + "/";
        }
        String dsl = """
                {
                  "name": "降级续跑",
                  "nodes": [
                    {"id": "c", "type": "http.collect", "params": {"url": "%s"}},
                    {"id": "e", "type": "core.echo", "params": {"message": "继续跑"}}
                  ],
                  "edges": [{"from": "c", "to": "e"}]
                }
                """.formatted(deadUrl);
        long workflowId = extractLong(postJson("/api/workflows", dsl).getBody(), "workflowId");
        String detail = executeAndPoll(workflowId, 10);

        assertThat(detail).contains("SUCCESS");
        assertThat(detail).contains("DEGRADED");          // 降级显性，禁止静默
        assertThat(detail).contains("继续跑");
    }

    private String executeAndPoll(long workflowId, int maxSeconds) throws Exception {
        long executionId = extractLong(
                postJson("/api/workflows/" + workflowId + "/executions", "{}").getBody(), "executionId");
        for (int i = 0; i < maxSeconds * 5; i++) {
            String detail = rest.getForObject("/api/executions/" + executionId, String.class);
            if (detail != null && (detail.contains("SUCCESS") || detail.contains("FAILED"))) {
                return detail;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new AssertionError("执行未在 " + maxSeconds + "s 内到达终态");
    }

    private long extractLong(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        int start = idx + field.length() + 2;
        while (start < json.length() && !Character.isDigit(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}

package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * P1 DoD 端到端：保存 echo 链 → 异步触发 → 轮询执行详情 → 节点日志落库且 traceId 贯穿。
 * 测试用独立内存库，不碰本地文件库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:af-p1-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class WorkflowApiE2ETests {

    @Autowired
    TestRestTemplate rest;

    private static final String TWO_ECHO_DSL = """
            {
              "name": "双echo链",
              "trigger": {"type": "manual"},
              "nodes": [
                {"id": "a", "type": "core.echo", "params": {"message": "ping"}},
                {"id": "b", "type": "core.echo", "params": {"message": "pong"}}
              ],
              "edges": [{"from": "a", "to": "b"}]
            }
            """;

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void saveTriggerAndInspectExecutionEndToEnd() throws Exception {
        ResponseEntity<String> created = postJson("/api/workflows", TWO_ECHO_DSL);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).contains("\"code\":\"0\"");
        long workflowId = extractLong(created.getBody(), "workflowId");

        ResponseEntity<String> triggered = postJson("/api/workflows/" + workflowId + "/executions", "{}");
        assertThat(triggered.getStatusCode()).isEqualTo(HttpStatus.OK);
        long executionId = extractLong(triggered.getBody(), "executionId");

        // 轮询至终态（上限 5s）：只认执行级 status，节点级 status 也叫 SUCCESS，
        // 用全文包含判断会把"a 已完成、b 还在跑"的半截执行当成终态提前跳出（并行改造后这个窗口更宽）
        String detail = null;
        for (int i = 0; i < 25; i++) {
            detail = rest.getForObject("/api/executions/" + executionId, String.class);
            String execStatus = executionStatus(detail);
            if (execStatus != null && !execStatus.equals("RUNNING")) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        assertThat(detail).isNotNull();
        assertThat(detail).contains("SUCCESS");
        assertThat(detail).contains("\"traceId\"");
        assertThat(detail).contains("ping").contains("pong"); // 两个节点的输出都进了执行日志
        assertThat(detail).contains("\"nodeId\":\"a\"").contains("\"nodeId\":\"b\""); // 终态里两个节点都在
        assertThat(detail).contains("core.echo").doesNotContain("FAILED");
    }

    @Test
    void cyclicWorkflowRejectedAtSave() {
        String cyclic = """
                {
                  "name": "环",
                  "nodes": [
                    {"id": "x", "type": "core.echo", "params": {}},
                    {"id": "y", "type": "core.echo", "params": {}}
                  ],
                  "edges": [{"from": "x", "to": "y"}, {"from": "y", "to": "x"}]
                }
                """;
        ResponseEntity<String> resp = postJson("/api/workflows", cyclic);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("C0500").contains("环");
    }

    @Test
    void unknownNodeTypeRejectedAtSave() {
        String unknown = """
                {
                  "name": "幽灵",
                  "nodes": [{"id": "g", "type": "ghost.type", "params": {}}],
                  "edges": []
                }
                """;
        ResponseEntity<String> resp = postJson("/api/workflows", unknown);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("类型未注册");
    }

    @Test
    void malformedJsonRejected() {
        ResponseEntity<String> resp = postJson("/api/workflows", "{not-json");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("A0400");
    }

    /**
     * HTTP 层钉住错误报文只带一层默认文案前缀：advice 若把已拼好的 getMessage() 再喂给
     * ApiResponse.fail，客户端会看到"资源不存在: 资源不存在: workflow 999"。
     */
    @Test
    void missingWorkflowReturnsSinglePrefixedMessage() {
        ResponseEntity<String> resp = rest.getForEntity("/api/workflows/999999", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("\"code\":\"A0404\"")
                .contains("\"message\":\"资源不存在: workflow 999999\"")
                .doesNotContain("资源不存在: 资源不存在");
    }

    /** 极简取值：从 {"data":{"k":123}} 结构中提取数值字段（避免为测试引 JSON 库）。 */
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

    /**
     * 执行级状态：节点列表里也有 "status"，但只有执行记录的那一个后面紧跟 startedAt，
     * 用它做锚点，避免把"部分节点已完成"的 RUNNING 执行读成终态。
     */
    private String executionStatus(String json) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"status\":\"([A-Z_]+)\",\"startedAt\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}

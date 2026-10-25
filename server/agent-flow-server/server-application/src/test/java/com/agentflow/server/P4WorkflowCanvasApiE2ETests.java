package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * P4 画布后端契约：列表只回属主可见的摘要、更新递增版本并保留 layout、跨用户读写统一 404。
 * 这些端点是画布"打开/保存"按钮的唯一依赖，缺一个前端就只能是只读演示。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p4-canvas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=p4-canvas-secret-at-least-32-chars!!",
                "agentflow.security.rate-limit-per-minute=0",
                "agentflow.nodes.llm.api-key=",
                "agentflow.nodes.image.api-key="})
class P4WorkflowCanvasApiE2ETests {

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> call(HttpMethod method, String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String registerAndLogin(String username) {
        assertThat(call(HttpMethod.POST, "/api/auth/register",
                "{\"username\":\"" + username + "\",\"password\":\"passw0rd-123\"}", null).getBody())
                .contains("\"code\":\"0\"");
        return extractString(call(HttpMethod.POST, "/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"passw0rd-123\"}", null).getBody(), "token");
    }

    private static String dsl(String name, String message) {
        return "{\"name\":\"" + name + "\","
                + "\"trigger\":{\"type\":\"manual\"},"
                + "\"nodes\":[{\"id\":\"e\",\"type\":\"core.echo\",\"params\":{\"message\":\"" + message + "\"}}],"
                + "\"edges\":[],"
                + "\"layout\":{\"e\":{\"x\":320,\"y\":180}}}";
    }

    @Test
    void listUpdateAndOwnership() {
        String alice = registerAndLogin("p4canvas_a");
        String bob = registerAndLogin("p4canvas_b");

        String created = call(HttpMethod.POST, "/api/workflows", dsl("canvas-a", "v1"), alice).getBody();
        long id = Long.parseLong(extractString(created, "workflowId"));

        // 列表：只回自己的，且带 nodeCount/layout 之外的摘要字段
        String aliceList = call(HttpMethod.GET, "/api/workflows", null, alice).getBody();
        assertThat(aliceList).contains("\"name\":\"canvas-a\"", "\"nodeCount\":1", "\"version\":1");
        String bobList = call(HttpMethod.GET, "/api/workflows", null, bob).getBody();
        assertThat(bobList).doesNotContain("canvas-a");

        // 打开：layout 原样返回，画布才能还原位置
        assertThat(call(HttpMethod.GET, "/api/workflows/" + id, null, alice).getBody())
                .contains("\"layout\"", "\"x\":320", "\"y\":180");

        // 更新：版本 +1，内容与布局都覆盖
        assertThat(call(HttpMethod.PUT, "/api/workflows/" + id, dsl("canvas-a-v2", "v2"), alice).getBody())
                .contains("\"code\":\"0\"");
        String reopened = call(HttpMethod.GET, "/api/workflows/" + id, null, alice).getBody();
        assertThat(reopened).contains("canvas-a-v2", "\"message\":\"v2\"");
        assertThat(call(HttpMethod.GET, "/api/workflows", null, alice).getBody()).contains("\"version\":2");

        // 跨用户更新/列表：统一 404 / 不出现
        assertThat(call(HttpMethod.PUT, "/api/workflows/" + id, dsl("hijack", "x"), bob).getBody())
                .contains("A0404");
        assertThat(call(HttpMethod.GET, "/api/workflows/" + id, null, bob).getBody()).contains("A0404");

        // 保存即校验：非法 DAG（环）不能借更新入口落库
        String cyclic = "{\"name\":\"bad\",\"nodes\":["
                + "{\"id\":\"a\",\"type\":\"core.echo\",\"params\":{}},"
                + "{\"id\":\"b\",\"type\":\"core.echo\",\"params\":{}}],"
                + "\"edges\":[{\"from\":\"a\",\"to\":\"b\"},{\"from\":\"b\",\"to\":\"a\"}]}";
        assertThat(call(HttpMethod.PUT, "/api/workflows/" + id, cyclic, alice).getBody())
                .contains("C0500");
    }

    /**
     * 画布设置（生成字数）必须能存能取：前端"保存"写的是 DSL 根 settings，
     * 一旦后端解析/入库把它吃掉，节点里的"留空走画布设置"就永远读不到，字数配置形同虚设。
     * 顺带核对四个审计字段确实透到了列表接口（前端卡片显示"创建人/最近修改人"依赖它）。
     */
    @Test
    void canvasSettingsAndAuditColumnsSurviveApiRoundTrip() {
        String owner = registerAndLogin("p4canvas_set");
        String withSettings = "{\"name\":\"canvas-set\","
                + "\"trigger\":{\"type\":\"manual\"},"
                + "\"settings\":{\"wechatLength\":2600,\"xhsLength\":900},"
                + "\"nodes\":[{\"id\":\"c\",\"type\":\"copy.write\",\"params\":{\"mode\":\"wechat\"}}],"
                + "\"edges\":[]}";
        long id = Long.parseLong(extractString(
                call(HttpMethod.POST, "/api/workflows", withSettings, owner).getBody(), "workflowId"));

        assertThat(call(HttpMethod.GET, "/api/workflows/" + id, null, owner).getBody())
                .contains("\"settings\"", "\"wechatLength\":2600", "\"xhsLength\":900");

        String list = call(HttpMethod.GET, "/api/workflows", null, owner).getBody();
        assertThat(list).contains("\"creator\":\"p4canvas_set\"", "\"updater\":\"p4canvas_set\"")
                .contains("\"updateTime\"");

        // 更新后：updater 落到操作人，create_time 不受影响（只有 update_time 走应用层刷新）
        assertThat(call(HttpMethod.PUT, "/api/workflows/" + id, withSettings, owner).getBody())
                .contains("\"code\":\"0\"");
        assertThat(call(HttpMethod.GET, "/api/workflows", null, owner).getBody()).contains("\"version\":2");
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

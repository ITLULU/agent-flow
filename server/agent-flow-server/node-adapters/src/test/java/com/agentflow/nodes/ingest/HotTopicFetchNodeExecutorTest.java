package com.agentflow.nodes.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.StubHttp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * hotspot.fetch：多源合并去重、单源失败不影响其它源、零条目留痕、全失败才异常、降级空列表。
 * 打桩一律走 endpointOverride（生产源是公开站点，不能进单测）。
 */
class HotTopicFetchNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 7L, 42L);

    private static NodeInput input(String sources, String stubUrl, Map<String, Object> extra) {
        Map<String, Object> params = new java.util.HashMap<>(extra);
        params.put("sources", sources);
        params.put("endpointOverride", "{\"" + sources + "\":\"" + stubUrl + "\"}");
        return new NodeInput(params, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(NodeOutput out) {
        return (List<Map<String, Object>>) out.data().get("items");
    }

    @Test
    void mergesDedupesAndStampsSource() throws Exception {
        String body = "[{\"title\":\"话题 A\",\"hot\":\"120万\",\"url\":\"https://x/1\"},"
                + "{\"title\":\"话题 A\",\"hot\":\"重复标题应被去重\"},"
                + "{\"title\":\"话题 B\",\"url\":\"https://x/2\"},{\"title\":\"  \"}]";
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, body))) {
            NodeOutput out = new HotTopicFetchNodeExecutor()
                    .execute(input("stub", stub.baseUrl() + "/hot", Map.of()), CTX);

            assertThat(items(out)).extracting(m -> m.get("title"))
                    .containsExactly("话题 A", "话题 B");
            assertThat(items(out).get(0)).containsEntry("source", "stub").containsEntry("hot", "120万");
            assertThat(out.data()).containsEntry("count", 2);
            assertThat(out.data()).containsEntry("okSources", List.of("stub"));
            assertThat(out.data()).containsEntry("failedSources", List.of());
        }
    }

    @Test
    void limitCapsMergedItems() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200,
                "[{\"title\":\"一\"},{\"title\":\"二\"},{\"title\":\"三\"}]"))) {
            NodeOutput out = new HotTopicFetchNodeExecutor()
                    .execute(input("stub", stub.baseUrl() + "/hot", Map.of("limit", 2)), CTX);
            assertThat(out.data()).containsEntry("count", 2);
        }
    }

    @Test
    void usesRegisteredParserWhenOverridingKnownSource() throws Exception {
        // 微博真实结构：data.realtime[].word/num —— 覆盖地址但保留官方解析器
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200,
                "{\"data\":{\"realtime\":[{\"word\":\"微博热榜词\",\"num\":98765}]}}"))) {
            NodeOutput out = new HotTopicFetchNodeExecutor()
                    .execute(input("weibo", stub.baseUrl(), Map.of()), CTX);
            assertThat(items(out)).singleElement().satisfies(m -> {
                assertThat(m).containsEntry("title", "微博热榜词").containsEntry("hot", "98765");
            });
        }
    }

    @Test
    void oneSourceFailsOthersStillDeliver() throws Exception {
        // weibo 走官方解析器（data.realtime[]），baidu 源 5xx 失败但不能拖垮整次取数
        try (StubHttp ok = new StubHttp(ex -> StubHttp.respond(ex, 200,
                "{\"data\":{\"realtime\":[{\"word\":\"活着的源\",\"num\":100}]}}"));
             StubHttp bad = new StubHttp(ex -> StubHttp.respond(ex, 500, "boom"))) {
            Map<String, Object> params = Map.of(
                    "sources", "weibo,baidu",
                    "endpointOverride", "{\"weibo\":\"" + ok.baseUrl() + "\",\"baidu\":\"" + bad.baseUrl() + "\"}");
            NodeOutput out = new HotTopicFetchNodeExecutor().execute(new NodeInput(params, Map.of()), CTX);

            assertThat(out.data()).containsEntry("okSources", List.of("weibo"));
            assertThat((List<?>) out.data().get("failedSources")).hasSize(1)
                    .allSatisfy(f -> assertThat(String.valueOf(f)).contains("C0003"));
            assertThat(out.data()).containsEntry("count", 1);
        }
    }

    @Test
    void structurallyChangedResponseIsTracedNotSilentlyEmpty() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, "{\"data\":{\"unexpected\":1}}"))) {
            assertThatThrownBy(() -> new HotTopicFetchNodeExecutor()
                    .execute(input("zhihu", stub.baseUrl(), Map.of()), CTX))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR);
                        assertThat(e.getMessage()).contains("疑似接口结构变更");
                    });
        }
    }

    @Test
    void unknownSourceWithoutOverrideFailsLoudly() {
        assertThatThrownBy(() -> new HotTopicFetchNodeExecutor().execute(
                new NodeInput(Map.of("sources", "not-registered"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessage()).contains("未注册"));
    }

    @Test
    void malformedOverrideIsBadRequest() {
        assertThatThrownBy(() -> new HotTopicFetchNodeExecutor().execute(
                new NodeInput(Map.of("sources", "weibo", "endpointOverride", "{not json"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void degradeContinuesWithEmptyItems() {
        NodeOutput out = new HotTopicFetchNodeExecutor().degrade(
                new NodeInput(Map.of(), Map.of()), new RuntimeException("站点全挂")).orElseThrow();
        assertThat(out.degraded()).isTrue();
        assertThat(out.data()).containsEntry("items", List.of()).containsEntry("count", 0);
        assertThat(out.degradeReason()).contains("热榜不可用");
    }

    @Test
    void contractValuesPinned() {
        HotTopicFetchNodeExecutor executor = new HotTopicFetchNodeExecutor();
        assertThat(executor.type()).isEqualTo("hotspot.fetch");
        assertThat(executor.timeout().toSeconds()).isEqualTo(30);
        RetryPolicy retry = executor.retryPolicy();
        assertThat(retry.maxAttempts()).isEqualTo(1);
        assertThat(retry.firstBackoffMs()).isEqualTo(800L);
    }
}

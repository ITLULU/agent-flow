package com.agentflow.nodes.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.nodes.StubHttp;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** http.collect：正常解析 + 三异常分类 + 降级，契约逐项钉死。 */
class HttpCollectNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 1L, 1L);

    private static final String PAGE = """
            <html><head><title>每周热文</title></head>
            <body>  正文一   段落二
            <a class="item" href="https://a.com/1">文章一</a>
            <a class="item" href="https://a.com/2">文章二</a>
            </body></html>
            """;

    @Test
    void parsesTitleTextAndSelectorItems() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, PAGE))) {
            HttpCollectNodeExecutor executor = new HttpCollectNodeExecutor();
            NodeOutput out = executor.execute(new NodeInput(Map.of(
                    "url", stub.baseUrl() + "/page",
                    "cssSelector", "a.item",
                    "attr", "href"), Map.of()), CTX);

            assertThat(out.degraded()).isFalse();
            assertThat(out.data()).containsEntry("title", "每周热文");
            assertThat(String.valueOf(out.data().get("text"))).contains("正文一 段落二");
            assertThat(asList(out.data().get("items")))
                    .containsExactly("https://a.com/1", "https://a.com/2");
        }
    }

    @Test
    void truncatesLongTextToMaxChars() throws Exception {
        String big = "<html><body>" + "字".repeat(500) + "</body></html>";
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, big))) {
            NodeOutput out = new HttpCollectNodeExecutor().execute(new NodeInput(Map.of(
                    "url", stub.baseUrl(), "maxChars", 100), Map.of()), CTX);
            String text = String.valueOf(out.data().get("text"));
            assertThat(text).hasSize(100 + "...[截断]".length()).endsWith("...[截断]");
        }
    }

    @Test
    void protocolErrorOnNon2xx() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 500, "boom"))) {
            assertThatThrownBy(() -> new HttpCollectNodeExecutor().execute(
                    new NodeInput(Map.of("url", stub.baseUrl()), Map.of()), CTX))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
        }
    }

    @Test
    void connectionRefusedMapsToUpstreamRejected() throws Exception {
        assertThatThrownBy(() -> new HttpCollectNodeExecutor().execute(
                new NodeInput(Map.of("url", StubHttp.closedPortUrl()), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_REJECTED));
    }

    @Test
    void slowResponseMapsToUpstreamTimeout() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> {
            sleep(400);
            StubHttp.respond(ex, 200, PAGE);
        })) {
            HttpCollectNodeExecutor fast = new HttpCollectNodeExecutor(Duration.ofMillis(100));
            assertThatThrownBy(() -> fast.execute(
                    new NodeInput(Map.of("url", stub.baseUrl()), Map.of()), CTX))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));
        }
    }

    @Test
    void missingUrlIsNonRetryableBadRequest() {
        assertThatThrownBy(() -> new HttpCollectNodeExecutor().execute(new NodeInput(Map.of(), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void degradeOutputsEmptyTextWithVisibleFlag() {
        NodeOutput degraded = new HttpCollectNodeExecutor()
                .degrade(new NodeInput(Map.of("url", "https://x.example"), Map.of()),
                        new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "读取超时"))
                .orElseThrow();
        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.degradeReason()).contains("采集失败");
        assertThat(degraded.data()).containsEntry("url", "https://x.example").containsEntry("text", "");
    }

    @Test
    void contractNumbersPinned() {
        HttpCollectNodeExecutor executor = new HttpCollectNodeExecutor();
        assertThat(executor.timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(executor.retryPolicy().maxAttempts()).isEqualTo(2);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> asList(Object v) {
        try {
            return (java.util.List<String>) v;
        } catch (ClassCastException e) {
            throw new AssertionError(v);
        }
    }
}

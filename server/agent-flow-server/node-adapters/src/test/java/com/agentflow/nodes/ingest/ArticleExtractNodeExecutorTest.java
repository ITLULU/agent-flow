package com.agentflow.nodes.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.nodes.StubHttp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** article.extract：正文提纯（挑容器、剥噪声）、元信息抽取、selector 强制指定、异常与降级。 */
class ArticleExtractNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 1L, 1L);

    /** 30 段填充文本，保证 js_content 超过 MIN_BODY_CHARS=200 的判定门槛。 */
    private static String filler(int paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= paragraphs; i++) {
            sb.append("<p>第").append(i).append("段：内容创作的复利来自把同一件事讲得越来越清楚，")
                    .append("而不是越讲越多。</p>");
        }
        return sb.toString();
    }

    private static String page() {
        return """
                <html><head>
                <title>原页面标题</title>
                <meta property="og:title" content="公众号真实标题">
                <meta name="author" content="张三的写作台">
                </head><body>
                <nav>导航噪声</nav>
                <script>var tracking = "统计脚本";</script>
                <div class="rich_media_meta_list">投诉/广告噪声</div>
                <div id="js_content">
                """ + filler(30) + """
                <img src="/pic/a.png">
                <img src="data:image/png;base64,AAAA">
                </div>
                </body></html>
                """;
    }

    @Test
    void picksSemanticContainerAndStripsNoise() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, page()))) {
            NodeOutput out = new ArticleExtractNodeExecutor().execute(
                    new NodeInput(Map.of("url", stub.baseUrl() + "/article"), Map.of()), CTX);

            Map<String, Object> data = out.data();
            assertThat(data).containsEntry("title", "公众号真实标题").containsEntry("author", "张三的写作台");
            String text = String.valueOf(data.get("text"));
            assertThat(text).contains("第1段").contains("第30段");
            assertThat(text).doesNotContain("统计脚本").doesNotContain("导航噪声").doesNotContain("投诉/广告噪声");
            assertThat((List<?>) data.get("paragraphs")).hasSize(30);
            assertThat((int) data.get("wordCount")).isPositive();
        }
    }

    @Test
    void resolvesRelativeImageUrlsAndSkipsDataUri() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, page()))) {
            NodeOutput out = new ArticleExtractNodeExecutor().execute(
                    new NodeInput(Map.of("url", stub.baseUrl() + "/article"), Map.of()), CTX);
            assertThat(out.data().get("images"))
                    .isEqualTo(List.of(stub.baseUrl() + "/pic/a.png"));
        }
    }

    @Test
    void includeImagesFalseOmitsImagesField() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, page()))) {
            NodeOutput out = new ArticleExtractNodeExecutor().execute(
                    new NodeInput(Map.of("url", stub.baseUrl(), "includeImages", false), Map.of()), CTX);
            assertThat(out.data()).doesNotContainKey("images");
        }
    }

    @Test
    void maxCharsTruncatesBody() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, page()))) {
            NodeOutput out = new ArticleExtractNodeExecutor().execute(
                    new NodeInput(Map.of("url", stub.baseUrl(), "maxChars", 100), Map.of()), CTX);
            assertThat(String.valueOf(out.data().get("text"))).hasSize(100 + "...[截断]".length())
                    .endsWith("...[截断]");
        }
    }

    @Test
    void explicitSelectorOverridesDensityPick() throws Exception {
        String html = "<html><body><div id=\"js_content\">" + filler(30)
                + "</div><section class=\"mine\">只做这一段。</section></body></html>";
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, html))) {
            NodeOutput out = new ArticleExtractNodeExecutor().execute(
                    new NodeInput(Map.of("url", stub.baseUrl(), "selector", "section.mine"), Map.of()), CTX);
            assertThat(String.valueOf(out.data().get("text"))).isEqualTo("只做这一段。");
        }
    }

    @Test
    void missingUrlIsBadRequest() {
        assertThatThrownBy(() -> new ArticleExtractNodeExecutor().execute(new NodeInput(Map.of(), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void nonHtmlResponseStillExtractsSomething() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, "纯文本素材，没有标签。"))) {
            NodeOutput out = new ArticleExtractNodeExecutor().execute(
                    new NodeInput(Map.of("url", stub.baseUrl()), Map.of()), CTX);
            assertThat(String.valueOf(out.data().get("text"))).contains("纯文本素材");
        }
    }

    @Test
    void upstreamHttpErrorMapsToProtocolError() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 403, "forbidden"))) {
            assertThatThrownBy(() -> new ArticleExtractNodeExecutor().execute(
                    new NodeInput(Map.of("url", stub.baseUrl()), Map.of()), CTX))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
        }
    }

    @Test
    void connectionRefusedMapsToUpstreamRejected() throws Exception {
        assertThatThrownBy(() -> new ArticleExtractNodeExecutor().execute(
                new NodeInput(Map.of("url", StubHttp.closedPortUrl()), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_REJECTED));
    }

    @Test
    void degradeKeepsPipelineAliveWithEmptyMaterial() {
        NodeOutput out = new ArticleExtractNodeExecutor().degrade(
                new NodeInput(Map.of("url", "https://x"), Map.of()), new RuntimeException("超时")).orElseThrow();
        assertThat(out.degraded()).isTrue();
        assertThat(out.data()).containsEntry("text", "").containsEntry("paragraphs", List.of())
                .containsEntry("wordCount", 0);
    }

    @Test
    void contractValuesPinned() {
        ArticleExtractNodeExecutor executor = new ArticleExtractNodeExecutor();
        assertThat(executor.type()).isEqualTo("article.extract");
        assertThat(executor.timeout().toSeconds()).isEqualTo(25);
        assertThat(executor.retryPolicy().maxAttempts()).isEqualTo(1);
        assertThat(executor.retryPolicy().firstBackoffMs()).isEqualTo(1000L);
    }
}

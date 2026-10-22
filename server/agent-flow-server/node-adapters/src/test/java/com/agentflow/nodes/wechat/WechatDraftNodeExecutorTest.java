package com.agentflow.nodes.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.StubHttp;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** wechat.draft：token→素材→草稿 API 序列、token 缓存、errcode 分级映射、发布永不降级。 */
class WechatDraftNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 1L, 1L);

    /** 可脚本化的微信 API 桩：响应体可替换、各端点调用次数与请求体可断言。 */
    private static final class WechatStub implements AutoCloseable {
        volatile String tokenResponse = "{\"access_token\":\"tok-1\",\"expires_in\":7200}";
        volatile String materialResponse = "{\"media_id\":\"thumb-1\"}";
        volatile String draftResponse = "{\"errcode\":0,\"media_id\":\"draft-9\"}";
        volatile long tokenSleepMs;
        final AtomicInteger tokenCalls = new AtomicInteger();
        final List<String> draftBodies = Collections.synchronizedList(new ArrayList<>());
        final List<String> materialBodies = Collections.synchronizedList(new ArrayList<>());
        final StubHttp server;

        WechatStub() throws IOException {
            server = new StubHttp(this::route);
        }

        private void route(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/cgi-bin/token" -> {
                    tokenCalls.incrementAndGet();
                    if (tokenSleepMs > 0) {
                        try {
                            Thread.sleep(tokenSleepMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    StubHttp.respond(ex, 200, tokenResponse);
                }
                case "/cgi-bin/material/add_material" -> {
                    materialBodies.add(StubHttp.readBody(ex));
                    StubHttp.respond(ex, 200, materialResponse);
                }
                case "/cgi-bin/draft/add" -> {
                    draftBodies.add(StubHttp.readBody(ex));
                    StubHttp.respond(ex, 200, draftResponse);
                }
                case "/cover.jpg" -> StubHttp.respondBytes(ex, 200, new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
                default -> StubHttp.respond(ex, 404, "{}");
            }
        }

        String url(String p) {
            return server.baseUrl() + p;
        }

        @Override
        public void close() {
            server.close();
        }
    }

    private WechatStub wechat;

    @BeforeEach
    void startStub() throws Exception {
        wechat = new WechatStub();
    }

    @AfterEach
    void stopStub() {
        wechat.close();
    }

    private WechatDraftNodeExecutor executor(Duration callTimeout) {
        return new WechatDraftNodeExecutor("wx-appid", "wx-secret", wechat.server.baseUrl(), callTimeout);
    }

    @Test
    void fullFlowTokenUploadMaterialThenDraft() {
        NodeOutput out = executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of(
                "title", "AI 周报第 1 期",
                "content", "<p>本周精选</p>",
                "thumbUrl", wechat.url("/cover.jpg")), Map.of()), CTX);

        assertThat(out.degraded()).isFalse();
        assertThat(out.data()).containsEntry("draftMediaId", "draft-9").containsEntry("hasThumb", true);
        assertThat(wechat.server.requestLog()).containsExactly(
                "GET /cgi-bin/token", "GET /cover.jpg",
                "POST /cgi-bin/material/add_material", "POST /cgi-bin/draft/add");
        assertThat(wechat.draftBodies.get(0))
                .contains("AI 周报第 1 期").contains("<p>本周精选</p>").contains("thumb_media_id");
        assertThat(wechat.materialBodies.get(0)).contains("agentflow").contains("cover.jpg").contains("image/jpeg");
    }

    @Test
    void accessTokenIsCachedAcrossExecutions() {
        WechatDraftNodeExecutor executor = executor(Duration.ofSeconds(5));
        NodeInput input = new NodeInput(Map.of("title", "T", "content", "C"), Map.of());
        executor.execute(input, CTX);
        executor.execute(input, CTX);
        // token 端点有日配额，两次执行必须只取一次 token
        assertThat(wechat.tokenCalls.get()).isEqualTo(1);
        assertThat(wechat.draftBodies).hasSize(2);
    }

    @Test
    void contentFallsBackToUpstreamOutput() {
        executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of("title", "T"),
                Map.of("gen", Map.of("content", "<p>上游改写稿</p>"))), CTX);
        assertThat(wechat.draftBodies.get(0)).contains("上游改写稿");
    }

    @Test
    void copyWriteOutputDrivesTitleAndParagraphs() {
        // 创作闭环里 wechat.draft 只接上游，不再让人手抄标题正文
        NodeInput input = new NodeInput(Map.of("digest", ""), Map.of(
                "c", Map.of("title", "秋天，把书一本本读完",
                        "digest", "一份照着读的秋日书单",
                        "body", "第一段。\n\n第二段。")));
        NodeOutput out = executor(Duration.ofSeconds(5)).execute(input, CTX);

        String draft = wechat.draftBodies.get(0);
        assertThat(draft).contains("秋天，把书一本本读完")
                .contains("<p>第一段。</p>").contains("<p>第二段。</p>")
                .contains("一份照着读的秋日书单");
        assertThat(out.data()).containsEntry("title", "秋天，把书一本本读完").containsEntry("hasThumb", false);
    }

    @Test
    void coverImageUpstreamBecomesThumbMaterial() {
        NodeOutput out = executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of("title", "T", "content", "C"),
                Map.of("i", Map.of("images", List.of(Map.of("url", wechat.url("/cover.jpg")))))), CTX);

        assertThat(out.data()).containsEntry("hasThumb", true);
        assertThat(wechat.server.requestLog()).contains("GET /cover.jpg", "POST /cgi-bin/material/add_material");
        assertThat(wechat.draftBodies.get(0)).contains("thumb_media_id").contains("thumb-1");
    }

    @Test
    void plainTextIsEscapedSoRawAngleBracketsCannotForgeTags() {
        executor(Duration.ofSeconds(5)).execute(new NodeInput(
                Map.of("title", "T", "content", "A & B\n<div>不该被当成标签</div>"), Map.of()), CTX);
        assertThat(wechat.draftBodies.get(0)).contains("A &amp; B").contains("&lt;div&gt;")
                .doesNotContain("<div>");
    }

    @Test
    void htmlContentIsPassedThroughUntouched() {
        executor(Duration.ofSeconds(5)).execute(new NodeInput(
                Map.of("title", "T", "content", "<section>作者自己排的版</section>"), Map.of()), CTX);
        assertThat(wechat.draftBodies.get(0)).contains("<section>作者自己排的版</section>");
    }

    @Test
    void missingTitleAndBodyBothFailFastWithUpstreamHint() {
        assertThatThrownBy(() -> executor(Duration.ofSeconds(5))
                .execute(new NodeInput(Map.of(), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("copy.write");
                });
    }

    @Test
    void draftWithoutThumbSkipsMaterialUpload() {
        NodeOutput out = executor(Duration.ofSeconds(5)).execute(
                new NodeInput(Map.of("title", "T", "content", "C"), Map.of()), CTX);
        assertThat(out.data()).containsEntry("hasThumb", false);
        assertThat(wechat.server.requestLog()).doesNotContain("POST /cgi-bin/material/add_material");
    }

    @Test
    void badCredentialIsNonRetryableBadRequest() {
        wechat.tokenResponse = "{\"errcode\":40013,\"errmsg\":\"invalid appid\"}";
        assertThatThrownBy(() -> executor(Duration.ofSeconds(5)).execute(
                new NodeInput(Map.of("title", "T", "content", "C"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("40013");
                });
    }

    @Test
    void wechatBusyMapsToRetryableTimeout() {
        wechat.draftResponse = "{\"errcode\":-1,\"errmsg\":\"system error\"}";
        assertThatThrownBy(() -> executor(Duration.ofSeconds(5)).execute(
                new NodeInput(Map.of("title", "T", "content", "C"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));
    }

    @Test
    void expiredTokenClearsCacheSoRetryGetsFreshOne() {
        WechatDraftNodeExecutor executor = executor(Duration.ofSeconds(5));
        NodeInput input = new NodeInput(Map.of("title", "T", "content", "C"), Map.of());
        wechat.draftResponse = "{\"errcode\":40001,\"errmsg\":\"token invalid\"}";
        assertThatThrownBy(() -> executor.execute(input, CTX)).isInstanceOf(ApiException.class);
        int callsAfterFirst = wechat.tokenCalls.get();

        wechat.draftResponse = "{\"errcode\":0,\"media_id\":\"draft-ok\"}";
        NodeOutput retry = executor.execute(input, CTX);
        // 40001 必须触发缓存失效：第二次执行重新取 token，且成功
        assertThat(wechat.tokenCalls.get()).isEqualTo(callsAfterFirst + 1);
        assertThat(retry.data()).containsEntry("draftMediaId", "draft-ok");
    }

    @Test
    void missingConfigAndParamsAreBadRequest() {
        WechatDraftNodeExecutor noCred =
                new WechatDraftNodeExecutor("", "", wechat.server.baseUrl(), Duration.ofSeconds(5));
        assertThatThrownBy(() -> noCred.execute(new NodeInput(Map.of("title", "T", "content", "C"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        assertThatThrownBy(() -> executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of("content", "C"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST)); // 缺 title

        assertThatThrownBy(() -> executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of("title", "T"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST)); // 无正文可用
    }

    @Test
    void connectionRefusedMapsToRejected() throws Exception {
        WechatDraftNodeExecutor executor = new WechatDraftNodeExecutor(
                "wx-appid", "wx-secret", StubHttp.closedPortUrl(), Duration.ofSeconds(5));
        assertThatThrownBy(() -> executor.execute(new NodeInput(Map.of("title", "T", "content", "C"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_REJECTED));
    }

    @Test
    void slowWechatApiMapsToTimeout() {
        wechat.tokenSleepMs = 400;
        assertThatThrownBy(() -> executor(Duration.ofMillis(100)).execute(
                new NodeInput(Map.of("title", "T", "content", "C"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));
    }

    @Test
    void publishingNeverDegradesByDesign() {
        // 契约钉死：发半截内容比不发更糟——发布节点失败必须显性 FAILED，不允许静默降级路径
        assertThat(executor(Duration.ofSeconds(5)).degrade(
                new NodeInput(Map.of("title", "T"), Map.of()), new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "x")))
                .isEmpty();
    }

    @Test
    void contractNumbersPinned() {
        WechatDraftNodeExecutor executor = executor(Duration.ofSeconds(5));
        assertThat(executor.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(executor.retryPolicy()).isEqualTo(new RetryPolicy(2, 1500L));
    }
}

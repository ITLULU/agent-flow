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
        volatile String uploadimgResponse = null; // null = 每次返回不同的永久 URL
        volatile String draftResponse = "{\"errcode\":0,\"media_id\":\"draft-9\"}";
        volatile long tokenSleepMs;
        final AtomicInteger tokenCalls = new AtomicInteger();
        final AtomicInteger uploadimgCalls = new AtomicInteger();
        final List<String> draftBodies = Collections.synchronizedList(new ArrayList<>());
        final List<String> materialBodies = Collections.synchronizedList(new ArrayList<>());
        final List<String> uploadimgBodies = Collections.synchronizedList(new ArrayList<>());
        final StubHttp server;

        WechatStub() throws IOException {
            server = new StubHttp(this::route);
        }

        /** 微信只看字节魔数判格式，桩里的"图片"必须真带 PNG 头。 */
        static byte[] png(int bytes) {
            byte[] out = new byte[bytes];
            out[0] = (byte) 0x89;
            out[1] = 'P';
            out[2] = 'N';
            out[3] = 'G';
            return out;
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
                case "/cgi-bin/media/uploadimg" -> {
                    uploadimgBodies.add(StubHttp.readBody(ex));
                    int n = uploadimgCalls.incrementAndGet();
                    StubHttp.respond(ex, 200, uploadimgResponse != null
                            ? uploadimgResponse : "{\"url\":\"http://mmbiz.qpic.cn/up-" + n + "\"}");
                }
                case "/cgi-bin/draft/add" -> {
                    draftBodies.add(StubHttp.readBody(ex));
                    StubHttp.respond(ex, 200, draftResponse);
                }
                case "/cover.jpg" -> StubHttp.respondBytes(ex, 200, new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
                case "/img1.png" -> StubHttp.respondBytes(ex, 200, png(2048));
                case "/img2.png" -> StubHttp.respondBytes(ex, 200, png(4096));
                case "/big.png" -> StubHttp.respondBytes(ex, 200, png(1024 * 1024 + 64));
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
    void creationOutputWinsOverIngestNodeWithSameKeys() {
        // 外链改写链路 a(article.extract)→b(copy.write)→…→f(wechat.draft)：
        // 上游按节点 id 字典序取第一个非空值时，a 的原文标题/原文正文会盖掉 b 的新稿，
        // 草稿箱里躺着的就是一篇原文（2026-10-25 17:33 实跑踩到，draftMediaId 可查）。
        NodeInput input = new NodeInput(Map.of(), Map.of(
                "a", Map.of("title", "10个工作不内耗法则，让你无痛上班", "text", "原文第一段内容。"),
                "b", Map.of("title", "10个不内耗开关，一按就松绑", "body", "新稿第一段。\n\n新稿第二段。",
                        "digest", "新稿摘要")));
        executor(Duration.ofSeconds(5)).execute(input, CTX);

        String sent = wechat.draftBodies.get(0);
        assertThat(sent).contains("10个不内耗开关，一按就松绑").contains("新稿第一段").contains("新稿摘要");
        assertThat(sent).doesNotContain("让你无痛上班").doesNotContain("原文第一段内容");
    }

    @Test
    void ingestTextStillUsedWhenNoCreationOutputExists() {
        // 纯采集链路没有 copy.write 时，仍要能交付上游拿到的正文（键优先级回退不能变成"什么都不发"）
        NodeInput input = new NodeInput(Map.of(), Map.of(
                "a", Map.of("title", "采集到的原文标题", "text", "采集到的原文正文。")));
        executor(Duration.ofSeconds(5)).execute(input, CTX);

        assertThat(wechat.draftBodies.get(0)).contains("采集到的原文标题").contains("采集到的原文正文");
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
                .contains("第一段。</p>").contains("第二段。</p>")
                .contains("<p style="). // 排版器只认内联 style：微信会清洗 class，无 style 等于没排版
                contains("一份照着读的秋日书单");
        assertThat(out.data()).containsEntry("title", "秋天，把书一本本读完").containsEntry("hasThumb", false);
    }

    @Test
    void plainBodyIsLaidOutWithNumberedSectionsAndInlineStyles() {
        // 用户口径的"没有格式"根因就在这里：正文必须带审美层次交付，而不是一串裸 <p>
        executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of(), Map.of(
                "c", Map.of("title", "T",
                        "body", "起手一段。\n\n## 先说结论\n\n结论段。\n\n> 一句金句。"))), CTX);

        String draft = wechat.draftBodies.get(0);
        assertThat(draft).contains(">01</span>").contains("先说结论")
                .contains("border-left:3px solid").contains("一句金句")
                .doesNotContain("class=").doesNotContain("<style");
    }

    @Test
    void unknownThemeIsRejectedInsteadOfQuietlySwappingPalette() {
        assertThatThrownBy(() -> executor(Duration.ofSeconds(5)).execute(
                new NodeInput(Map.of("title", "T", "content", "正文", "theme", "purple"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("purple").contains("moss");
                });
    }

    @Test
    void chosenThemeDrivesAccentColorIntoBody() {
        executor(Duration.ofSeconds(5)).execute(new NodeInput(
                Map.of("title", "T", "content", "这里有个**重点**", "theme", "azure"), Map.of()), CTX);

        assertThat(wechat.draftBodies.get(0)).contains("#2b6cb0").doesNotContain("#2f6f5e");
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

    // ---- T4 主图 + 正文配图层次 ----

    private static Map<String, Object> imageOut(String kind, String... urls) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (String u : urls) {
            images.add(Map.of("url", u, "kind", kind));
        }
        return Map.of("images", images);
    }

    @Test
    void illustrationUpstreamBecomesPermanentInlineImagesInsideBody() {
        NodeOutput out = executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of(), Map.of(
                "c", Map.of("title", "T", "body", "起手段。\n\n## 第一节\n\n内容甲。\n\n## 第二节\n\n内容乙。"),
                "i1", imageOut("cover", wechat.url("/cover.jpg")),
                "i2", imageOut("illustration", wechat.url("/img1.png"), wechat.url("/img2.png")))), CTX);

        String draft = wechat.draftBodies.get(0);
        assertThat(draft).contains("http://mmbiz.qpic.cn/up-1").contains("http://mmbiz.qpic.cn/up-2");
        assertThat(wechat.server.requestLog()).contains("POST /cgi-bin/media/uploadimg", "POST /cgi-bin/material/add_material");
        assertThat(wechat.uploadimgCalls.get()).isEqualTo(2);
        assertThat(out.data()).containsEntry("hasThumb", true)
                .containsEntry("inlineImages", List.of("http://mmbiz.qpic.cn/up-1", "http://mmbiz.qpic.cn/up-2"))
                .containsEntry("warnings", List.of());
    }

    @Test
    void inlineImagesArePlacedAfterHeadingsNotStackedAtTheEnd() {
        executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of(), Map.of(
                "c", Map.of("title", "T", "body", "## 第一节\n\n内容甲。\n\n## 第二节\n\n内容乙。"),
                "i2", imageOut("illustration", wechat.url("/img1.png"), wechat.url("/img2.png")))), CTX);

        String draft = wechat.draftBodies.get(0);
        assertThat(draft.indexOf("第一节")).isLessThan(draft.indexOf("up-1"));
        assertThat(draft.indexOf("up-1")).isLessThan(draft.indexOf("第二节")); // 图跟着上一节走
        assertThat(draft.indexOf("第二节")).isLessThan(draft.indexOf("up-2"));
    }

    @Test
    void coverOnlyPipelinePutsNoImageInsideTheBody() {
        // 主图不能既当封面又当配图：正文里再出现一张一样的封面，读者以为发错了稿
        NodeOutput out = executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of(), Map.of(
                "c", Map.of("title", "T", "body", "正文。"),
                "i1", imageOut("cover", wechat.url("/cover.jpg")))), CTX);

        assertThat(wechat.draftBodies.get(0)).doesNotContain("<img");
        assertThat(wechat.uploadimgCalls.get()).isZero();
        assertThat(out.data()).containsEntry("hasThumb", true).containsEntry("inlineImages", List.of());
    }

    @Test
    void oversizedIllustrationIsSkippedWithVisibleWarning() {
        NodeOutput out = executor(Duration.ofSeconds(5)).execute(new NodeInput(
                Map.of("title", "T", "content", "正文。",
                        "inlineImages", List.of(wechat.url("/big.png"), wechat.url("/img1.png"))), Map.of()), CTX);

        assertThat(out.data()).containsEntry("inlineImages", List.of("http://mmbiz.qpic.cn/up-1"));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) out.data().get("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("跳过").contains("1MB").contains("big.png");
        assertThat(wechat.uploadimgCalls.get()).isEqualTo(1); // 超限的图不占接口配额
    }

    @Test
    void allIllustrationsTooBigStillWarnsSoEmptyGalleryIsNotASuccess() {
        NodeOutput out = executor(Duration.ofSeconds(5)).execute(new NodeInput(
                Map.of("title", "T", "content", "正文。", "inlineImages", List.of(wechat.url("/big.png"))), Map.of()), CTX);

        assertThat(out.data()).containsEntry("inlineImages", List.of());
        assertThat(String.valueOf(out.data().get("warnings"))).contains("没有一张能进正文");
    }

    @Test
    void explicitInlineImagesParamOverridesUpstream() {
        executor(Duration.ofSeconds(5)).execute(new NodeInput(
                Map.of("title", "T", "content", "正文。", "inlineImages", List.of(wechat.url("/img2.png"))),
                Map.of("i2", imageOut("illustration", wechat.url("/img1.png")))), CTX);

        assertThat(wechat.uploadimgCalls.get()).isEqualTo(1);
        assertThat(wechat.uploadimgBodies.get(0)).contains("filename=\"inline.png\"");
    }

    @Test
    void uploadimgRejectionFailsTheDraftInsteadOfPublishingTextOnly() {
        wechat.uploadimgResponse = "{\"errcode\":45009,\"errmsg\":\"reach daily quota\"}";
        assertThatThrownBy(() -> executor(Duration.ofSeconds(5)).execute(new NodeInput(
                Map.of("title", "T", "content", "正文。", "inlineImages", List.of(wechat.url("/img1.png"))), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR);
                    assertThat(e.getMessage()).contains("正文插图上传").contains("45009");
                });
    }

    @Test
    void authorHtmlBodyKeepsItsOwnMarkupAndOnlyAppendsImages() {
        executor(Duration.ofSeconds(5)).execute(new NodeInput(
                Map.of("title", "T", "content", "<section>作者自己排的版</section>",
                        "inlineImages", List.of(wechat.url("/img1.png"))), Map.of()), CTX);

        String content = contentOf(0);
        assertThat(content).startsWith("<section>作者自己排的版</section><section")
                .contains("<img src=\"http://mmbiz.qpic.cn/up-1\"");
    }

    /** draft/add 的请求体是 JSON，正文断言要先解一层，否则内层引号都是转义过的。 */
    private String contentOf(int draftIndex) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(wechat.draftBodies.get(draftIndex))
                    .path("articles").get(0).path("content").asText();
        } catch (Exception e) {
            throw new IllegalStateException("draft 请求体不是合法 JSON: " + wechat.draftBodies.get(draftIndex), e);
        }
    }

    @Test
    void pngBytesDecideMimeAndExtensionNotTheUrlSuffix() {
        executor(Duration.ofSeconds(5)).execute(new NodeInput(
                Map.of("title", "T", "content", "正文。", "inlineImages", List.of(wechat.url("/img1.png"))), Map.of()), CTX);

        assertThat(wechat.uploadimgBodies.get(0)).contains("Content-Type: image/png");
        // 封面那张桩返的是 JPEG 字节，文件名也该是 jpg 而不是硬编码 cover.jpg
        executor(Duration.ofSeconds(5)).execute(new NodeInput(Map.of("title", "T2", "content", "正文。"),
                Map.of("i1", imageOut("cover", wechat.url("/cover.jpg")))), CTX);
        assertThat(wechat.materialBodies.get(0)).contains("Content-Type: image/jpeg").contains("filename=\"cover.jpg\"");
    }
}

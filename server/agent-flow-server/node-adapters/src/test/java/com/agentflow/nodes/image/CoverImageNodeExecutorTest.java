package com.agentflow.nodes.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.nodes.FakeContentStore;
import com.agentflow.nodes.StubHttp;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * image.cover：按比例出图 + 落地暂存 + 提示词回退链 + 防重复计费（noRetry）+ 无图降级。
 * DashScope 异步协议由桩服务器复刻（提交 → 轮询 → 取图字节），图片 URL 由桩自身端口回推，
 * 这样"取回远端图"这一步也走本地回路，单测不碰外网。
 */
class CoverImageNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 1L, 55L);
    private static final byte[] PNG = "PNG-BYTES".getBytes(StandardCharsets.UTF_8);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstImage(NodeOutput out) {
        return (Map<String, Object>) ((List<?>) out.data().get("images")).get(0);
    }

    private static Map<String, Object> params(Map<String, Object> extra) {
        Map<String, Object> p = new HashMap<>(extra);
        p.put("pollIntervalMs", 10);
        p.put("maxWaitMs", 4000);
        return p;
    }

    /** 三合一桩：提交（捕获请求体/头）→ 轮询成功 → 同端口提供图片字节。 */
    private static StubHttp dashScope(AtomicReference<String> submitted, AtomicReference<String> auth,
                                      AtomicReference<String> async) throws Exception {
        return new StubHttp(ex -> {
            String path = ex.getRequestURI().getPath();
            String self = "http://127.0.0.1:" + ex.getLocalAddress().getPort();
            if (path.contains("image-synthesis")) {
                submitted.set(StubHttp.readBody(ex));
                auth.set(ex.getRequestHeaders().getFirst("Authorization"));
                async.set(ex.getRequestHeaders().getFirst("X-DashScope-Async"));
                StubHttp.respond(ex, 200, "{\"output\":{\"task_id\":\"task-77\"}}");
            } else if (path.startsWith("/api/v1/tasks/")) {
                StubHttp.respond(ex, 200, "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":"
                        + "[{\"url\":\"" + self + "/img/1.png\"}]}}");
            } else {
                StubHttp.respondBytes(ex, 200, PNG);
            }
        });
    }

    @Test
    void xhsRatioProducesPortraitAndStagesBytes() throws Exception {
        FakeContentStore store = new FakeContentStore();
        AtomicReference<String> submitted = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> async = new AtomicReference<>();
        try (StubHttp stub = dashScope(submitted, auth, async)) {
            NodeOutput out = new CoverImageNodeExecutor("sk-test", stub.baseUrl(), store)
                    .execute(new NodeInput(params(Map.of("prompt", "秋日读书清单封面",
                            "ratio", "xhs", "kind", "cover", "n", 1)), Map.of()), CTX);

            assertThat(submitted.get()).contains("\"size\":\"768*1024\"").contains("秋日读书清单封面");
            assertThat(auth.get()).isEqualTo("Bearer sk-test");
            assertThat(async.get()).isEqualTo("enable"); // 缺异步头 DashScope 会走同步长连接

            Map<String, Object> image = firstImage(out);
            assertThat(image).containsEntry("ratio", "3:4").containsEntry("size", "768*1024")
                    .containsEntry("width", 768).containsEntry("height", 1024)
                    .containsEntry("bytes", PNG.length);
            assertThat(image.get("fileName")).isEqualTo("cover-1-3x4.png");
            assertThat(String.valueOf(image.get("localPath"))).isEqualTo(".staging/55/cover-1-3x4.png");
            assertThat(store.log).containsExactly("stage:55:cover-1-3x4.png:9");
            assertThat(out.data()).containsEntry("stored", true).containsEntry("count", 1)
                    .containsEntry("taskId", "task-77").containsEntry("ratio", "3:4");
        }
    }

    @Test
    void saveFalseSkipsContentStore() throws Exception {
        FakeContentStore store = new FakeContentStore();
        try (StubHttp stub = dashScope(new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>())) {
            NodeOutput out = new CoverImageNodeExecutor("sk", stub.baseUrl(), store)
                    .execute(new NodeInput(params(Map.of("prompt", "P", "save", false)), Map.of()), CTX);

            assertThat(out.data()).containsEntry("stored", false);
            assertThat(store.log).isEmpty();
            Map<String, Object> image = firstImage(out);
            assertThat(image).doesNotContainKey("localPath");
            assertThat(String.valueOf(image.get("url"))).endsWith("/img/1.png");
        }
    }

    @Test
    void storeAbsentStillDeliversRemoteUrls() throws Exception {
        try (StubHttp stub = dashScope(new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>())) {
            NodeOutput out = new CoverImageNodeExecutor("sk", stub.baseUrl())
                    .execute(new NodeInput(params(Map.of("prompt", "P")), Map.of()), CTX);
            assertThat(out.data()).containsEntry("stored", false).containsEntry("count", 1);
        }
    }

    @Test
    void explicitSizeOverridesRatio() throws Exception {
        AtomicReference<String> submitted = new AtomicReference<>();
        try (StubHttp stub = dashScope(submitted, new AtomicReference<>(), new AtomicReference<>())) {
            NodeOutput out = new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of("prompt", "P", "size", "1200*512")), Map.of()), CTX);

            assertThat(submitted.get()).contains("\"size\":\"1200*512\"");
            assertThat(out.data()).containsEntry("size", "1200*512");
        }
    }

    @Test
    void outOfRangeSizeRejectedBeforeSubmit() throws Exception {
        try (StubHttp stub = dashScope(new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>())) {
            CoverImageNodeExecutor executor = new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore());
            assertThatThrownBy(() -> executor.execute(
                    new NodeInput(params(Map.of("prompt", "P", "size", "2400*2400")), Map.of()), CTX))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
            assertThat(stub.countOf("POST /api/v1/services/aigc/text2image/image-synthesis")).isZero();
        }
    }

    @Test
    void nIsClampedToFour() throws Exception {
        AtomicReference<String> submitted = new AtomicReference<>();
        try (StubHttp stub = dashScope(submitted, new AtomicReference<>(), new AtomicReference<>())) {
            new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of("prompt", "P", "n", 9)), Map.of()), CTX);
            assertThat(submitted.get()).contains("\"n\":4");
        }
    }

    @Test
    void promptFallsBackToUpstreamTitle() throws Exception {
        AtomicReference<String> submitted = new AtomicReference<>();
        Map<String, Object> upstream = Map.of("n2", Map.of("title", "秋日读书清单"));
        try (StubHttp stub = dashScope(submitted, new AtomicReference<>(), new AtomicReference<>())) {
            NodeOutput out = new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of("kind", "inline", "style", "暖色插画")), upstream), CTX);

            assertThat(submitted.get()).contains("秋日读书清单").contains("内文配图").contains("暖色插画");
            assertThat(String.valueOf(out.data().get("prompt"))).contains("《秋日读书清单》");
        }
    }

    @Test
    void missingPromptAndUpstreamTitleIsBadRequest() throws Exception {
        try (StubHttp stub = dashScope(new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>())) {
            assertThatThrownBy(() -> new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of()), Map.of()), CTX))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    @Test
    void failedTaskMapsToProtocolError() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> {
            if (ex.getRequestURI().getPath().contains("image-synthesis")) {
                StubHttp.respond(ex, 200, "{\"output\":{\"task_id\":\"t-1\"}}");
            } else {
                StubHttp.respond(ex, 200,
                        "{\"output\":{\"task_status\":\"FAILED\",\"code\":\"IP\",\"message\":\"涉侵权\"}}");
            }
        })) {
            assertThatThrownBy(() -> new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of("prompt", "P")), Map.of()), CTX))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR);
                        assertThat(e.getMessage()).contains("生图任务失败");
                    });
        }
    }

    @Test
    void missingTaskIdMapsToProtocolError() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 200, "{\"request_id\":\"r-1\"}"))) {
            assertThatThrownBy(() -> new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of("prompt", "P")), Map.of()), CTX))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getMessage()).contains("缺少 task_id"));
        }
    }

    @Test
    void degradeDeliversWithoutImages() {
        NodeOutput out = new CoverImageNodeExecutor("sk", "http://x", new FakeContentStore())
                .degrade(new NodeInput(Map.of(), Map.of()), new RuntimeException("额度用尽")).orElseThrow();
        assertThat(out.degraded()).isTrue();
        assertThat(out.data()).containsEntry("images", List.of()).containsEntry("count", 0)
                .containsEntry("stored", false);
        assertThat(out.degradeReason()).contains("按无图发布");
    }

    @Test
    void contractValuesPinned() {
        CoverImageNodeExecutor executor = new CoverImageNodeExecutor("sk", "http://x");
        assertThat(executor.type()).isEqualTo("image.cover");
        assertThat(executor.timeout().toSeconds()).isEqualTo(180);
        assertThat(executor.retryPolicy().maxAttempts()).isZero(); // 按次计费节点，禁止重试
    }

    // ---------- P4B：场景 / 画风 / 参考配方 / 四宫格 ----------

    @Test
    void sceneDrivesSizeAndComposedPrompt() throws Exception {
        AtomicReference<String> submitted = new AtomicReference<>();
        try (StubHttp stub = dashScopeMulti(1, PNG, submitted)) {
            NodeOutput out = new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of("scene", "xhs-cover", "topic", "秋日咖啡馆",
                            "promptStyle", "film", "referenceStyle", "creamy tones, soft window light")), Map.of()), CTX);

            assertThat(submitted.get()).contains("\"size\":\"768*1024\"")
                    .contains("秋日咖啡馆")
                    .contains("creamy tones")        // 参考图配方进了正向提示词
                    .contains("portra 400")          // 画风预设展开成英文风格词
                    .contains("negative_prompt");    // 反 AI 味默认负向词已随请求发出
            assertThat(out.data()).containsEntry("ratio", "3:4").containsEntry("size", "768*1024");
        }
    }

    @Test
    void customNegativePromptReachesDashScope() throws Exception {
        AtomicReference<String> submitted = new AtomicReference<>();
        try (StubHttp stub = dashScopeMulti(1, PNG, submitted)) {
            new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of("scene", "wechat-cover", "topic", "季度复盘",
                            "negativePrompt", "cartoon, 3d render")), Map.of()), CTX);
            assertThat(submitted.get()).contains("\"negative_prompt\":\"cartoon, 3d render\"")
                    .doesNotContain("plastic skin");
        }
    }

    @Test
    void collage2x2KeepsFourSinglesAndProducesGrid() throws Exception {
        FakeContentStore store = new FakeContentStore();
        byte[] tile = realPng(64, 64);
        try (StubHttp stub = dashScopeMulti(4, tile, new AtomicReference<>())) {
            NodeOutput out = new CoverImageNodeExecutor("sk", stub.baseUrl(), store)
                    .execute(new NodeInput(params(Map.of("scene", "xhs-body", "topic", "桌面平铺",
                            "collage", "2x2")), Map.of()), CTX);

            assertThat(out.data()).containsEntry("count", 4).doesNotContainKey("collageError");
            @SuppressWarnings("unchecked")
            Map<String, Object> collage = (Map<String, Object>) out.data().get("collage");
            assertThat(collage).containsEntry("format", "2x2").containsEntry("ratio", "1:1")
                    .containsEntry("localPath", ".staging/55/collage-2x2-1x1.png");
            assertThat(((Number) collage.get("bytes")).intValue()).isGreaterThan(tile.length);
            assertThat(store.log).hasSize(5); // 4 张单图 + 1 张拼图都落了暂存
            assertThat(store.log.get(4)).startsWith("stage:55:collage-2x2-1x1.png:");
        }
    }

    /** 拼图失败不许静默：单张照常交付，collageError 必须显性回吐（否则调用方以为"这次本就没拼图"）。 */
    @Test
    void collageFailureIsReportedNotSwallowed() throws Exception {
        try (StubHttp stub = dashScopeMulti(4, PNG, new AtomicReference<>())) {
            NodeOutput out = new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of("topic", "T", "collage", "2x2")), Map.of()), CTX);

            assertThat(out.data()).containsEntry("count", 4).doesNotContainKey("collage");
            assertThat(String.valueOf(out.data().get("collageError"))).contains("四宫格拼接失败");
            assertThat(out.degraded()).isFalse(); // 单张仍在，不算整体降级
        }
    }

    @Test
    void collageWithFewerThanFourUrlsExplainsShortfall() throws Exception {
        try (StubHttp stub = dashScopeMulti(2, PNG, new AtomicReference<>())) {
            NodeOutput out = new CoverImageNodeExecutor("sk", stub.baseUrl(), new FakeContentStore())
                    .execute(new NodeInput(params(Map.of("topic", "T", "collage", "2x2")), Map.of()), CTX);

            assertThat(out.data()).containsEntry("count", 2).doesNotContainKey("collage");
            assertThat(String.valueOf(out.data().get("collageError"))).contains("实际只拿到 2 张");
        }
    }

    /** 多张结果的桩：results 数组给 count 条同端口 URL，图片字节统一回 imageBytes。 */
    private static StubHttp dashScopeMulti(int count, byte[] imageBytes, AtomicReference<String> submitted)
            throws Exception {
        return new StubHttp(ex -> {
            String path = ex.getRequestURI().getPath();
            String self = "http://127.0.0.1:" + ex.getLocalAddress().getPort();
            if (path.contains("image-synthesis")) {
                submitted.set(StubHttp.readBody(ex));
                StubHttp.respond(ex, 200, "{\"output\":{\"task_id\":\"task-multi\"}}");
            } else if (path.startsWith("/api/v1/tasks/")) {
                StringBuilder results = new StringBuilder();
                for (int i = 1; i <= count; i++) {
                    results.append(i > 1 ? "," : "").append("{\"url\":\"").append(self)
                            .append("/img/").append(i).append(".png\"}");
                }
                StubHttp.respond(ex, 200,
                        "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[" + results + "]}}");
            } else {
                StubHttp.respondBytes(ex, 200, imageBytes);
            }
        });
    }

    /** 真 PNG：拼图路径要 ImageIO 能解出来的字节，假字节只会走到 collageError 分支。 */
    private static byte[] realPng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(214, 122, 88));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}

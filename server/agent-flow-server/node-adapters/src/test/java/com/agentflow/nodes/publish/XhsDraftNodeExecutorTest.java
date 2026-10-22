package com.agentflow.nodes.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.nodes.FakeContentStore;
import com.agentflow.nodes.StubHttp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * xhs.draft：本地图文包（默认，published=false）、平台硬约束进 warnings、
 * 配了 endpoint 才外发、外发失败降级不丢包。
 */
class XhsDraftNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 3L, 88L);

    private static Map<String, Object> upstreamCopy() {
        return Map.of("copy", Map.of("mode", "xiaohongshu", "title", "三伏天自律清单",
                "body", "正文内容", "hashtags", List.of("#自律", "#夏日"),
                "coverLines", List.of("封面行一"), "images", List.of(Map.of("url", "https://x/1.png"))));
    }

    @Test
    void buildsLocalPackageWithoutEgress() {
        FakeContentStore store = new FakeContentStore();
        store.staged.put(88L, new ArrayList<>(List.of("cover-1-3x4.png")));

        NodeOutput out = new XhsDraftNodeExecutor(store)
                .execute(new NodeInput(Map.of(), upstreamCopy()), CTX);

        assertThat(out.data()).containsEntry("published", false)
                .containsEntry("channel", "xhs-local-package")
                .containsEntry("artifactId", 1L)
                .containsEntry("titleLen", "三伏天自律清单".length())
                .containsEntry("imageCount", 1);
        assertThat((List<?>) out.data().get("warnings")).isEmpty();
        assertThat(store.files.get(1L)).containsOnlyKeys("note.txt", "xhs-package.json");
        assertThat(store.files.get(1L).get("note.txt"))
                .startsWith("三伏天自律清单").contains("正文内容").contains("#自律 #夏日");
        assertThat(store.files.get(1L).get("xhs-package.json"))
                .contains("\"hashtags\"").contains("coverLines");
        assertThat(store.attached.get(1L)).containsExactly("1/assets/cover-1-3x4.png");
    }

    @Test
    void platformLimitsBecomeWarningsNotFailures() {
        FakeContentStore store = new FakeContentStore();
        Map<String, Object> params = Map.of(
                "title", "这个标题太长了超过了小红书允许的二十个字所以要提醒精简",
                "body", "正".repeat(1001),
                "hashtags", "");
        NodeOutput out = new XhsDraftNodeExecutor(store).execute(new NodeInput(params, Map.of()), CTX);

        assertThat((List<String>) out.data().get("warnings"))
                .anyMatch(w -> w.contains("标题") && w.contains("20"))
                .anyMatch(w -> w.contains("正文") && w.contains("1000"))
                .anyMatch(w -> w.contains("无配图"))
                .anyMatch(w -> w.contains("无标签"));
        assertThat(out.degraded()).isFalse(); // 校验只提示，不阻断交付
    }

    @Test
    void imageListOverCapIsTrimmed() {
        FakeContentStore store = new FakeContentStore();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("https://x/" + i + ".png");
        }
        NodeOutput out = new XhsDraftNodeExecutor(store)
                .execute(new NodeInput(Map.of("title", "T", "body", "B", "images", many), Map.of()), CTX);

        assertThat(out.data()).containsEntry("imageCount", 18);
        assertThat((List<String>) out.data().get("warnings"))
                .anyMatch(w -> w.contains("超出 18 张上限"));
    }

    @Test
    void appendsToExistingArtifactWhenIdGiven() {
        FakeContentStore store = new FakeContentStore();
        store.artifacts.put(7L, new java.util.HashMap<>(Map.of("id", 7L)));
        store.files.put(7L, new java.util.LinkedHashMap<>());
        store.attached.put(7L, new ArrayList<>());

        NodeOutput out = new XhsDraftNodeExecutor(store)
                .execute(new NodeInput(Map.of("artifactId", "7", "title", "T", "body", "B"), Map.of()), CTX);

        assertThat(out.data()).containsEntry("artifactId", 7L);
        assertThat(store.log).noneMatch(l -> l.startsWith("insert:")); // 不再新建成品行
    }

    @Test
    void missingTitleOrBodyIsRejected() {
        FakeContentStore store = new FakeContentStore();
        assertThatThrownBy(() -> new XhsDraftNodeExecutor(store)
                .execute(new NodeInput(Map.of("title", "只有标题"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(store.artifacts).isEmpty();
    }

    @Test
    void absentStoreIsConfigError() {
        assertThatThrownBy(() -> new XhsDraftNodeExecutor(null)
                .execute(new NodeInput(Map.of("title", "T", "body", "B"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessage()).contains("ContentStore 缺席"));
    }

    @Test
    void postsPackageOnlyWhenEndpointConfigured() throws Exception {
        FakeContentStore store = new FakeContentStore();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        try (StubHttp stub = new StubHttp(ex -> {
            body.set(StubHttp.readBody(ex));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            StubHttp.respond(ex, 200, "{\"code\":0,\"msg\":\"ok\"}");
        })) {
            NodeOutput out = new XhsDraftNodeExecutor(store).execute(new NodeInput(
                    Map.of("endpoint", stub.baseUrl() + "/publish", "accessToken", "tok-1",
                            "title", "T", "body", "B", "hashtags", List.of("#a")), Map.of()), CTX);

            assertThat(out.data()).containsEntry("published", true)
                    .containsEntry("channel", "xhs-open-api");
            assertThat(String.valueOf(out.data().get("response"))).contains("\"code\":0");
            assertThat(body.get()).contains("\"title\":\"T\"")
                    .doesNotContain("tok-1").doesNotContain("accessToken"); // token 只进 Header，不进包体
            assertThat(auth.get()).isEqualTo("Bearer tok-1");
        }
    }

    @Test
    void egressFailureDegradesToLocalPackage() {
        FakeContentStore store = new FakeContentStore();
        NodeOutput out = new XhsDraftNodeExecutor(store).degrade(
                new NodeInput(Map.of("endpoint", "https://x"), Map.of()),
                new RuntimeException("风控 461")).orElseThrow();

        assertThat(out.degraded()).isTrue();
        assertThat(out.data()).containsEntry("published", false)
                .containsEntry("channel", "xhs-local-package");
        assertThat(out.degradeReason()).contains("本地图文包");
    }

    @Test
    void contractValuesPinned() {
        XhsDraftNodeExecutor executor = new XhsDraftNodeExecutor(new FakeContentStore());
        assertThat(executor.type()).isEqualTo("xhs.draft");
        assertThat(executor.timeout().toSeconds()).isEqualTo(45);
        assertThat(executor.retryPolicy().maxAttempts()).isZero(); // 重试可能重复发帖
    }
}

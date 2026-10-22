package com.agentflow.nodes.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.nodes.FakeContentStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** publish.local：上游取数、kind 推断、frontmatter 成品、暂存搬迁、不降级不重试。 */
class LocalArchiveNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 9L, 77L);

    private static Map<String, Object> copyWechat() {
        return Map.of("mode", "wechat", "title", "深度工作", "body", "正文一\n正文二",
                "digest", "摘要文本", "hashtags", List.of("#效率", "#写作"),
                "keyPoints", List.of("金句一"), "coverLines", List.of("封面行"));
    }

    @Test
    void archivesUpstreamCopyAsArticleWithMarkdownAndMeta() {
        FakeContentStore store = new FakeContentStore();
        store.staged.put(77L, new java.util.ArrayList<>(List.of("cover-1-3x4.png")));
        Map<String, Object> upstream = Map.of(
                "copy", copyWechat(),
                "cover", Map.of("images", List.of(Map.of("url", "https://x/1.png", "ratio", "3:4"))));

        NodeOutput out = new LocalArchiveNodeExecutor(store)
                .execute(new NodeInput(Map.of("sourceUrl", "https://mp.example/a"), upstream), CTX);

        assertThat(out.data()).containsEntry("artifactId", 1L).containsEntry("kind", "article")
                .containsEntry("title", "深度工作").containsEntry("dir", "/fake/library/1")
                .containsEntry("fileCount", 3);
        assertThat(store.files.get(1L)).containsOnlyKeys("article.md", "meta.json");
        assertThat(store.log).anyMatch(l -> l.equals("insert:77:article"))
                .anyMatch(l -> l.startsWith("write:1:article.md:"))
                .anyMatch(l -> l.startsWith("write:1:meta.json:"))
                .anyMatch(l -> l.equals("attach:77:1"));
        assertThat(store.attached.get(1L)).containsExactly("1/assets/cover-1-3x4.png");

        String md = store.files.get(1L).get("article.md");
        assertThat(md).startsWith("---\n").contains("title: \"深度工作\"").contains("kind: article")
                .contains("digest: \"摘要文本\"").contains("hashtags: [\"#效率\", \"#写作\"]")
                .contains("# 深度工作").contains("> 摘要文本").contains("正文一").contains("#效率 #写作");
    }

    @Test
    void xhsModeMapsToNoteKind() {
        FakeContentStore store = new FakeContentStore();
        Map<String, Object> copy = Map.of("mode", "xiaohongshu", "title", "书单", "body", "正文");
        NodeOutput out = new LocalArchiveNodeExecutor(store)
                .execute(new NodeInput(Map.of(), Map.of("copy", copy)), CTX);
        assertThat(out.data()).containsEntry("kind", "note");
    }

    @Test
    void explicitParamsOverrideUpstream() {
        FakeContentStore store = new FakeContentStore();
        NodeOutput out = new LocalArchiveNodeExecutor(store).execute(new NodeInput(
                Map.of("title", "手工标题", "body", "手工正文", "kind", "gallery"),
                Map.of("copy", copyWechat())), CTX);

        assertThat(out.data()).containsEntry("title", "手工标题").containsEntry("kind", "gallery");
        assertThat(store.artifacts.get(1L).get("body")).isEqualTo("手工正文");
    }

    @Test
    void missingBodyIsRejected() {
        FakeContentStore store = new FakeContentStore();
        assertThatThrownBy(() -> new LocalArchiveNodeExecutor(store)
                .execute(new NodeInput(Map.of(), Map.of("other", Map.of("x", 1))), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(store.artifacts).isEmpty(); // 拒绝必须发生在插行之前
    }

    @Test
    void absentStoreIsConfigError() {
        assertThatThrownBy(() -> new LocalArchiveNodeExecutor(null)
                .execute(new NodeInput(Map.of("body", "有正文"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessage()).contains("ContentStore 缺席"));
    }

    @Test
    void neverDegradesAndNeverRetries() {
        LocalArchiveNodeExecutor executor = new LocalArchiveNodeExecutor(new FakeContentStore());
        assertThat(executor.degrade(new NodeInput(Map.of(), Map.of()), new RuntimeException("x"))).isEmpty();
        assertThat(executor.retryPolicy().maxAttempts()).isZero(); // 重试会插出第二条重复稿
        assertThat(executor.type()).isEqualTo("publish.local");
        assertThat(executor.timeout().toSeconds()).isEqualTo(30);
    }
}

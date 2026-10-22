package com.agentflow.nodes.copy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.nodes.llm.LlmGateway;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * copy.write：分节解析、素材/主题的三种来路、两种渠道的模板与提示词、失败语义（不降级）。
 * 模型用桩网关，提示词与解析口径是这里唯一要钉死的东西。
 */
class CopyWriteNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 1L, 1L);

    /** 记录最后一次请求、按脚本站答的桩网关。 */
    private static final class StubGateway implements LlmGateway {
        private final String answer;
        Request last;

        StubGateway(String answer) {
            this.answer = answer;
        }

        @Override
        public Reply chat(Request request) {
            this.last = request;
            return new Reply(answer, "qwen-plus", 1234);
        }
    }

    private static final String STRUCTURED = """
            【标题】别让周报吞噬你的深度工作
            【副标题】三个能立刻上手的节奏设计
            【摘要】把周报从"输出物"改造成"输入物"，注意力就回来了。
            【标签】#深度工作 #效率系统 #知识工作
            【金句】工具替你记录，你替自己思考。｜慢，是快的一种形态。
            【封面文案】把周报改成说明书
            【正文】
            第一段：问题在哪。
            第二段：三个做法。
            """;

    private static NodeOutput run(Map<String, Object> params, Map<String, Object> upstream, LlmGateway gateway) {
        return new CopyWriteNodeExecutor(() -> gateway)
                .execute(new NodeInput(params, upstream), CTX);
    }

    @Test
    void parsesStructuredSections() {
        NodeOutput out = run(Map.of("topic", "深度工作"), Map.of(), new StubGateway(STRUCTURED));

        assertThat(out.degraded()).isFalse();
        assertThat(out.data()).containsEntry("title", "别让周报吞噬你的深度工作")
                .containsEntry("subtitle", "三个能立刻上手的节奏设计")
                .containsEntry("mode", "wechat")
                .containsEntry("model", "qwen-plus")
                .containsEntry("totalTokens", 1234)
                .containsEntry("parseFallback", false);
        assertThat(String.valueOf(out.data().get("body"))).contains("第一段：问题在哪").contains("第二段");
        assertThat((List<?>) out.data().get("hashtags")).hasSize(3);
        assertThat((List<?>) out.data().get("keyPoints")).hasSize(2);
        assertThat(out.data().get("coverLines")).isEqualTo(List.of("把周报改成说明书"));
        assertThat((int) out.data().get("wordCount")).isPositive();
    }

    @Test
    void unstructuredReplyStillDeliversBodyWithFlag() {
        StubGateway gateway = new StubGateway("这就是模型随手写的一段话，没有任何分节标记。");
        NodeOutput out = run(Map.of("topic", "随便聊聊"), Map.of(), gateway);

        assertThat(out.data()).containsEntry("parseFallback", true);
        assertThat(String.valueOf(out.data().get("body"))).startsWith("这就是模型随手写的一段话");
        assertThat(out.data()).containsEntry("title", "");
        assertThat(String.valueOf(out.data().get("parseNote"))).contains("需人工补");
    }

    @Test
    void wechatModeUsesLongFormTemplateAndParams() {
        StubGateway gateway = new StubGateway(STRUCTURED);
        run(Map.of("mode", "gzh", "topic", "深度工作", "audience", "工作 3-8 年的知识工作者",
                "tone", "克制、有画面感", "length", 900, "temperature", 0.6), Map.of(), gateway);

        assertThat(gateway.last.system()).contains("【标题】"); // 模板里的硬格式要求
        assertThat(gateway.last.prompt()).contains("渠道：微信公众号长文").contains("主题：深度工作")
                .contains("目标读者：工作 3-8 年的知识工作者").contains("语气风格：克制、有画面感")
                .contains("正文约 900 字");
        assertThat(gateway.last.temperature()).isEqualTo(0.6);
        assertThat(gateway.last.prompt()).contains("无外部素材");
    }

    @Test
    void xhsAliasesSwitchToNoteTemplate() {
        StubGateway gateway = new StubGateway(STRUCTURED);
        NodeOutput out = run(Map.of("mode", "xhs", "topic", "秋日书单"), Map.of(), gateway);

        assertThat(out.data()).containsEntry("mode", "xiaohongshu");
        assertThat(gateway.last.prompt()).contains("渠道：小红书图文笔记");
        assertThat(gateway.last.system()).contains("小红书图文博主");

        StubGateway wechatGateway = new StubGateway(STRUCTURED);
        run(Map.of("mode", "wechat", "topic", "秋日书单"), Map.of(), wechatGateway);
        assertThat(wechatGateway.last.system()).contains("微信公众号主笔")
                .isNotEqualTo(gateway.last.system()); // 两套模板必须各走各的
    }

    @Test
    void materialComesFromUpstreamArticleExtract() {
        StubGateway gateway = new StubGateway(STRUCTURED);
        Map<String, Object> upstream = Map.of("n1",
                Map.of("title", "原文标题", "text", "原文正文素材，讲的是同样的主题。"));
        run(Map.of(), upstream, gateway);

        assertThat(gateway.last.prompt()).contains("主题：原文标题"); // 上游标题即主题
        assertThat(gateway.last.prompt()).contains("【素材】").contains("原文正文素材");
    }

    @Test
    void topicAndMaterialComeFromUpstreamHotspots() {
        StubGateway gateway = new StubGateway(STRUCTURED);
        Map<String, Object> upstream = Map.of("n1", Map.of("items", List.of(
                Map.of("title", "话题甲", "source", "weibo"),
                Map.of("title", "话题乙", "source", "baidu"))));
        run(Map.of(), upstream, gateway);

        assertThat(gateway.last.prompt()).contains("主题：话题甲");
        assertThat(gateway.last.prompt()).contains("- 话题乙（来源:baidu）");
    }

    @Test
    void explicitMaterialWinsOverUpstream() {
        StubGateway gateway = new StubGateway(STRUCTURED);
        Map<String, Object> upstream = Map.of("n1", Map.of("text", "上游素材"));
        run(Map.of("topic", "T", "material", "我自己给的素材"), upstream, gateway);
        assertThat(gateway.last.prompt()).contains("我自己给的素材").doesNotContain("上游素材");
    }

    @Test
    void blankReplyIsProtocolError() {
        assertThatThrownBy(() -> run(Map.of("topic", "T"), Map.of(), new StubGateway("   ")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void gatewayRuntimeExceptionIsTranslated() {
        LlmGateway boom = request -> {
            throw new IllegalStateException("SDK 炸了");
        };
        assertThatThrownBy(() -> run(Map.of("topic", "T"), Map.of(), boom))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR);
                    assertThat(e.getMessage()).contains("SDK 炸了");
                });
    }

    @Test
    void missingGatewayIsNonRetryableConfigError() {
        assertThatThrownBy(() -> run(Map.of("topic", "T"), Map.of(), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void missingTopicAndUpstreamIsRejected() {
        assertThatThrownBy(() -> run(Map.of(), Map.of(), new StubGateway(STRUCTURED)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessage()).contains("缺少主题"));
    }

    @Test
    void unsupportedModeIsRejected() {
        assertThatThrownBy(() -> run(Map.of("mode", "douyin", "topic", "T"), Map.of(), new StubGateway(STRUCTURED)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void neverDegradesSilently() {
        Optional<NodeOutput> degraded = new CopyWriteNodeExecutor(() -> new StubGateway(STRUCTURED))
                .degrade(new NodeInput(Map.of(), Map.of()), new RuntimeException("x"));
        assertThat(degraded).isEmpty(); // 半截文案不可交付：失败必须显性 FAILED
    }

    @Test
    void contractValuesPinned() {
        CopyWriteNodeExecutor executor = new CopyWriteNodeExecutor(() -> null);
        assertThat(executor.type()).isEqualTo("copy.write");
        assertThat(executor.timeout().toSeconds()).isEqualTo(120);
        assertThat(executor.retryPolicy().maxAttempts()).isEqualTo(1);
        assertThat(executor.retryPolicy().firstBackoffMs()).isEqualTo(2000L);
    }
}

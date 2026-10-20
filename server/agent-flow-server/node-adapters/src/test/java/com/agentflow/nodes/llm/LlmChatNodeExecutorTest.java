package com.agentflow.nodes.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** llm.chat：网关缺失快败、参数校验、上游素材拼接、异常穿透/兜底、降级显性。全部用假网关，不碰真实 SDK。 */
class LlmChatNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 1L, 1L);

    /** 记录请求、可脚本化返回/抛错的假网关。 */
    private static final class RecordingGateway implements LlmGateway {
        final List<Request> requests = new ArrayList<>();
        LlmGateway.Reply reply = new LlmGateway.Reply("生成的摘要", "qwen-plus", 123);
        RuntimeException error;

        @Override
        public Reply chat(Request request) {
            requests.add(request);
            if (error != null) {
                throw error;
            }
            return reply;
        }
    }

    private static LlmChatNodeExecutor node(LlmGateway gateway) {
        return new LlmChatNodeExecutor(() -> gateway);
    }

    @Test
    void sendsPromptModelSystemAndTemperature() {
        RecordingGateway gateway = new RecordingGateway();
        NodeOutput out = node(gateway).execute(new NodeInput(Map.of(
                "prompt", "写一篇公众号导语",
                "model", "qwen-max",
                "system", "你是编辑",
                "temperature", 0.7), Map.of()), CTX);

        assertThat(out.degraded()).isFalse();
        assertThat(out.data()).containsEntry("content", "生成的摘要")
                .containsEntry("model", "qwen-plus")
                .containsEntry("totalTokens", 123);
        LlmGateway.Request sent = gateway.requests.get(0);
        assertThat(sent.prompt()).isEqualTo("写一篇公众号导语");
        assertThat(sent.model()).isEqualTo("qwen-max");
        assertThat(sent.system()).isEqualTo("你是编辑");
        assertThat(sent.temperature()).isEqualTo(0.7);
    }

    @Test
    void appendsUpstreamTextAndContentWhenContextEnabled() {
        RecordingGateway gateway = new RecordingGateway();
        node(gateway).execute(new NodeInput(
                Map.of("prompt", "总结这些素材", "useUpstreamContext", true),
                Map.of("collect1", Map.of("text", "热榜正文A"),
                        "collect2", Map.of("content", "改写稿B"))), CTX);

        String prompt = gateway.requests.get(0).prompt();
        assertThat(prompt).contains("热榜正文A").contains("改写稿B").contains("【上游素材】");
    }

    @Test
    void missingGatewayConfigIsNonRetryableBadRequest() {
        assertThatThrownBy(() -> node(null).execute(new NodeInput(Map.of("prompt", "x"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("BAILIAN_API_KEY");
                });
        assertThatThrownBy(() -> new LlmChatNodeExecutor(() -> null)
                .execute(new NodeInput(Map.of("prompt", "x"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void missingPromptIsBadRequest() {
        assertThatThrownBy(() -> node(new RecordingGateway()).execute(new NodeInput(Map.of(), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void gatewayApiExceptionPropagatesUnchanged() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.error = new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "百炼超时");
        assertThatThrownBy(() -> node(gateway).execute(new NodeInput(Map.of("prompt", "x"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));
    }

    @Test
    void unclassifiedGatewayErrorIsWrappedAsProtocolError() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.error = new RuntimeException("SDK 内部炸了");
        assertThatThrownBy(() -> node(gateway).execute(new NodeInput(Map.of("prompt", "x"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void blankModelOutputIsProtocolError() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.reply = new LlmGateway.Reply("   ", "qwen-plus", 0);
        assertThatThrownBy(() -> node(gateway).execute(new NodeInput(Map.of("prompt", "x"), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void degradeOutputsEmptyContentNotSilentSuccess() {
        Optional<NodeOutput> degraded = node(null).degrade(new NodeInput(Map.of("prompt", "x"), Map.of()),
                new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "超时耗尽"));
        assertThat(degraded).isPresent();
        assertThat(degraded.get().degraded()).isTrue();
        assertThat(degraded.get().data()).containsEntry("content", "");
        assertThat(degraded.get().degradeReason()).contains("LLM 生成失败");
    }

    @Test
    void contractNumbersPinned() {
        LlmChatNodeExecutor executor = node(null);
        assertThat(executor.timeout()).isEqualTo(Duration.ofSeconds(90));
        // LLM 按 token 计费：重试必须收紧到 1 次，防配额风暴
        assertThat(executor.retryPolicy().maxAttempts()).isEqualTo(1);
    }
}

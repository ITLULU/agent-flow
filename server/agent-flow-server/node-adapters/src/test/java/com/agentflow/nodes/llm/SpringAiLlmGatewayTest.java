package com.agentflow.nodes.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** SpringAiLlmGateway：用假 ChatModel 验证请求组装与三异常转译，不访问真实百炼。 */
class SpringAiLlmGatewayTest {

    /** 记录 Prompt、可脚本化响应/抛错的假 ChatModel。 */
    private static final class FakeChatModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        ChatResponse response;
        RuntimeException error;

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (error != null) {
                throw error;
            }
            return response;
        }
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void usesRequestModelOverrideAndKeepsDefault() {
        FakeChatModel model = new FakeChatModel();
        model.response = response("hello");
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(model, "qwen-plus");

        LlmGateway.Reply reply = gateway.chat(new LlmGateway.Request("qwen-max", null, "写导语", null));
        assertThat(reply.content()).isEqualTo("hello");
        assertThat(reply.modelUsed()).isEqualTo("qwen-max");
        assertThat(((DashScopeChatOptions) model.prompts.get(0).getOptions()).getModel()).isEqualTo("qwen-max");

        gateway.chat(new LlmGateway.Request(null, null, "写导语", null));
        assertThat(((DashScopeChatOptions) model.prompts.get(1).getOptions()).getModel()).isEqualTo("qwen-plus");
    }

    @Test
    void systemMessagePrependsWhenProvided() {
        FakeChatModel model = new FakeChatModel();
        model.response = response("ok");
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(model, "qwen-plus");

        gateway.chat(new LlmGateway.Request(null, "你是公众号编辑", "写摘要", 0.3));
        List<Message> messages = model.prompts.get(0).getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((DashScopeChatOptions) model.prompts.get(0).getOptions()).getTemperature()).isEqualTo(0.3);
    }

    @Test
    void connectFailureMapsToUpstreamRejected() {
        FakeChatModel model = new FakeChatModel();
        model.error = new RuntimeException(new ConnectException("Connection refused"));
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(model, "qwen-plus");

        assertThatThrownBy(() -> gateway.chat(new LlmGateway.Request(null, null, "x", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_REJECTED));
    }

    @Test
    void timeoutMapsToUpstreamTimeout() {
        FakeChatModel model = new FakeChatModel();
        model.error = new IllegalStateException("read timed out");
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(model, "qwen-plus");

        assertThatThrownBy(() -> gateway.chat(new LlmGateway.Request(null, null, "x", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));
    }

    @Test
    void quotaOrUnknownErrorMapsToProtocolError() {
        FakeChatModel model = new FakeChatModel();
        model.error = new RuntimeException("429 Too Many Requests");
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(model, "qwen-plus");

        assertThatThrownBy(() -> gateway.chat(new LlmGateway.Request(null, null, "x", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void emptyGenerationsMapsToProtocolError() {
        FakeChatModel model = new FakeChatModel();
        model.response = new ChatResponse(List.of());
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(model, "qwen-plus");

        assertThatThrownBy(() -> gateway.chat(new LlmGateway.Request(null, null, "x", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
    }
}

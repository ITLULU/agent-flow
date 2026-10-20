package com.agentflow.nodes.llm;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelResponse;

/**
 * SpringAiLlmGateway — {@link LlmGateway} 的当前生产实现（Spring AI + Spring AI Alibaba / 百炼 DashScope）。
 *
 * 刻意收敛在实现类：ChatModel 类型不出本包——切换 SDK 的成本被封顶在一个文件。
 * 多模型口径：构造时给默认模型（qwen-plus 级别），每次请求可用 Request.model 覆盖，
 * 同一网关实例服务不同模型的节点，无需为每个模型建 Bean。
 *
 * 实例化只在 API key 存在时发生（server 装配层条件化），无 key 环境不会触发 DashScope 构造。
 */
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatModel chatModel;
    private final String defaultModel;

    /** 生产构造：直连 DashScope。只依赖 core 构件，不走 autoconfigure（无 key 启动炸的防线）。 */
    public SpringAiLlmGateway(String apiKey, String defaultModel) {
        this(buildDashScope(apiKey, defaultModel), defaultModel);
    }

    /** 测试/自定义 ChatModel 注入口子（如 openai-compatible 端点）。 */
    public SpringAiLlmGateway(ChatModel chatModel, String defaultModel) {
        this.chatModel = chatModel;
        this.defaultModel = defaultModel;
    }

    private static ChatModel buildDashScope(String apiKey, String defaultModel) {
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        DashScopeChatOptions options = new DashScopeChatOptions();
        options.setModel(defaultModel);
        return DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(options).build();
    }

    @Override
    public Reply chat(Request request) {
        DashScopeChatOptions options = new DashScopeChatOptions();
        options.setModel(request.model() != null && !request.model().isBlank() ? request.model() : defaultModel);
        if (request.temperature() != null) {
            options.setTemperature(request.temperature());
        }
        List<Message> messages = request.system() == null || request.system().isBlank()
                ? List.of(new UserMessage(request.prompt()))
                : List.of(new SystemMessage(request.system()), new UserMessage(request.prompt()));
        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(messages, options));
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e);
        }
        String content = extractText(response);
        if (content == null || content.isBlank()) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "模型返回空内容（choices 缺失或全空）");
        }
        Integer tokens = extractTotalTokens(response);
        return new Reply(content.trim(), options.getModel(), tokens);
    }

    private static String extractText(ModelResponse<?> modelResponse) {
        if (!(modelResponse instanceof ChatResponse response) || response.getResult() == null) {
            return null;
        }
        AssistantMessage output = response.getResult().getOutput();
        return output == null ? null : output.getText();
    }

    private static Integer extractTotalTokens(ChatResponse response) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        Number total = response.getMetadata().getUsage().getTotalTokens();
        return total == null ? null : total.intValue();
    }

    /** 契约：把 SDK 底层异常转译成三类 UPSTREAM_*，禁止裸奔。 */
    private static ApiException classify(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return new ApiException(ErrorCode.UPSTREAM_REJECTED, "百炼连接失败: " + t.getMessage());
            }
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException
                    || String.valueOf(t.getMessage()).contains("timed out")) {
                return new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "百炼调用超时: " + t.getMessage());
            }
        }
        return new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "百炼协议/配额异常: " + e.getMessage());
    }
}

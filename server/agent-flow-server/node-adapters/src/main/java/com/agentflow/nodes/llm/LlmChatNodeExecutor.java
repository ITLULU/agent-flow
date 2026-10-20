package com.agentflow.nodes.llm;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * llm.chat — 大模型对话节点。经 {@link LlmGateway} 门面调用，节点本身不感知任何模型 SDK。
 *
 * params: prompt(必填) / model(可选，覆盖默认模型) / system(可选) / temperature(可选)
 *         useUpstreamContext(可选 true：把所有上游节点的 text/content 拼进提示词，内容流水线主用法)
 * 输出: content / model / totalTokens
 *
 * 四件套口径：
 * - 超时：90s（LLM 长输出口径，远低于默认 60s 不够用的场景）。
 * - 重试：仅 1 次（LLM 按 token 计费，引擎层重试会重复消耗配额；429 类错误由网关侧退避）。
 * - 异常：网关未配置→A0400 不可重试；底层由网关转译为 UPSTREAM_*，节点兜底包装。
 * - 降级：输出空 content 续跑——下游（如 wechat.draft）可回退用采集原文发布，degraded=true 显性。
 */
public class LlmChatNodeExecutor implements NodeExecutor {

    public static final String TYPE = "llm.chat";

    private final Supplier<LlmGateway> gatewaySupplier;

    /**
     * @param gatewaySupplier 可为 null 或返回 null（表示未配置凭证），执行时转译为不可重试的配置错误。
     *                        用 Supplier 而非直接注入：节点 Bean 无条件注册（画布可存 DSL），
     *                        网关是否可用延迟到执行时刻判定。
     */
    public LlmChatNodeExecutor(Supplier<LlmGateway> gatewaySupplier) {
        this.gatewaySupplier = gatewaySupplier;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(90);
    }

    @Override
    public RetryPolicy retryPolicy() {
        return new RetryPolicy(1, 1500L);
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        LlmGateway gateway = gatewaySupplier == null ? null : gatewaySupplier.get();
        if (gateway == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "LLM 网关未配置（缺少 BAILIAN_API_KEY），llm.chat 无法执行");
        }
        String prompt = input.paramAsString("prompt", null);
        if (prompt == null || prompt.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "llm.chat 缺少必填参数 prompt");
        }
        if (asBool(input.param("useUpstreamContext"))) {
            String context = collectUpstreamText(input);
            if (!context.isBlank()) {
                prompt = prompt + "\n\n【上游素材】\n" + context;
            }
        }
        String model = input.paramAsString("model", null);
        String system = input.paramAsString("system", null);
        Double temperature = asDouble(input.param("temperature"));

        LlmGateway.Reply reply;
        try {
            reply = gateway.chat(new LlmGateway.Request(model, system, prompt, temperature));
        } catch (ApiException e) {
            throw e; // 网关已按契约分类，原样穿透
        } catch (RuntimeException e) {
            // 网关实现漏包装的最后兜底——任何 SDK 异常不得裸奔出节点层
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "LLM 网关异常: " + e.getMessage());
        }
        if (reply == null || reply.content() == null || reply.content().isBlank()) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "模型返回空内容");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("content", reply.content());
        data.put("model", reply.modelUsed() != null ? reply.modelUsed() : (model != null ? model : "default"));
        if (reply.totalTokens() != null) {
            data.put("totalTokens", reply.totalTokens());
        }
        return NodeOutput.ok(data);
    }

    @Override
    public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
        Map<String, Object> data = new HashMap<>();
        data.put("content", "");
        return Optional.of(NodeOutput.degraded(data, "LLM 生成失败，下游按原文处理: " + safeMessage(lastError)));
    }

    /** 拼接上游输出中的 text/content 字段（采集正文/改写稿），带来源节点 id 便于追溯。 */
    private static String collectUpstreamText(NodeInput input) {
        StringBuilder sb = new StringBuilder();
        input.upstream().forEach((nodeId, out) -> {
            if (out instanceof Map<?, ?> map) {
                Object t = map.get("text") != null ? map.get("text") : map.get("content");
                if (t != null && !String.valueOf(t).isBlank()) {
                    sb.append("<<<").append(nodeId).append(">>>\n").append(t).append("\n\n");
                }
            }
        });
        return sb.toString().trim();
    }

    private static boolean asBool(Object v) {
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}

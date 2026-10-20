package com.agentflow.nodes.llm;

/**
 * LlmGateway — 模型调用门面（框架可替换缝）。
 *
 * 纯 Java 接口，不引任何框架类型：workflow-engine / LlmChatNodeExecutor 只依赖本接口，
 * Spring AI / Spring AI Alibaba 只是当前唯一实现（SpringAiLlmGateway）。
 * 若未来切换 LangChain4j 或其它 SDK，只需新增一个实现类，引擎、DSL、执行日志零改动。
 *
 * 契约：实现类必须把底层异常转译为 ApiException(UPSTREAM_*)，禁止原始堆栈穿透到节点层之外。
 */
public interface LlmGateway {

    /**
     * @param model     可为 null，表示用网关默认模型（多模型可配置的节点级覆盖入口）
     * @param system    系统提示词，可为 null
     * @param prompt    用户提示词，必填
     * @param temperature 可为 null，用服务端默认
     */
    record Request(String model, String system, String prompt, Double temperature) {
    }

    /**
     * @param content    模型输出文本（非 null；空输出应由实现抛 UPSTREAM_PROTOCOL_ERROR，而不是返回空串）
     * @param modelUsed  实际使用的模型名（回写执行日志便于审计）
     * @param totalTokens 消耗 token 总数，未知为 null
     */
    record Reply(String content, String modelUsed, Integer totalTokens) {
    }

    Reply chat(Request request);
}

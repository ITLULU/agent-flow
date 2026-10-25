package com.agentflow.engine.spi;

import java.util.Map;

/**
 * 执行上下文：一次工作流执行的定位信息，traceId 与触发请求一致，贯穿节点日志。
 * settings 为画布级默认值（DSL 根节点透传），节点按"显式参数 > settings > 内置默认"取用。
 */
public record ExecutionContext(String traceId, long workflowId, long executionId, Map<String, Object> settings) {

    public ExecutionContext {
        if (settings == null) {
            settings = Map.of();
        }
    }

    public ExecutionContext(String traceId, long workflowId, long executionId) {
        this(traceId, workflowId, executionId, Map.of());
    }

    public Object setting(String key) {
        return settings.get(key);
    }
}

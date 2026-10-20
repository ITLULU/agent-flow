package com.agentflow.engine.spi;

/** 执行上下文：一次工作流执行的定位信息，traceId 与触发请求一致，贯穿节点日志。 */
public record ExecutionContext(String traceId, long workflowId, long executionId) {
}

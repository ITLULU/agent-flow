package com.agentflow.engine.model;

/** 引擎执行返回值（executionId 由调用方预创建，便于 API 立即回给客户端轮询）。 */
public record ExecutionResult(long executionId, ExecutionStatus status, java.util.List<NodeRunResult> nodeResults) {
}

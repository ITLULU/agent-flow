package com.agentflow.engine.model;

import java.util.Map;

/**
 * 节点执行结果快照（进 execution_node_log）。
 * reason：DEGRADED 记降级原因 / FAILED 记根因摘要，SUCCESS 为 null。
 */
public record NodeRunResult(String nodeId, String nodeType, NodeStatus status, int attempts,
                            long durationMs, Map<String, Object> output, String reason) {
}

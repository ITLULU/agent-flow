package com.agentflow.engine.spi;

import java.util.Map;

/**
 * 节点输出：data 必须非 null（无数据用空 Map）。
 * degraded=true 表示走了降级路径——执行日志必须露出该标志，禁止静默降级（skill §3）。
 */
public record NodeOutput(Map<String, Object> data, boolean degraded, String degradeReason) {

    public static NodeOutput ok(Map<String, Object> data) {
        return new NodeOutput(data, false, null);
    }

    public static NodeOutput degraded(Map<String, Object> data, String reason) {
        return new NodeOutput(data, true, reason);
    }
}

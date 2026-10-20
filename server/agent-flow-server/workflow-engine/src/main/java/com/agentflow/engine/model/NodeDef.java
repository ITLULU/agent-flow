package com.agentflow.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** DSL 节点定义。params 结构由各 NodeExecutor 自解释，引擎不感知语义。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeDef(String id, String type, Map<String, Object> params) {
}

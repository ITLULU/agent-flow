package com.agentflow.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** 工作流 DSL 全量定义：画布与引擎共享的唯一事实源。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(String name, TriggerDef trigger, List<NodeDef> nodes, List<EdgeDef> edges) {

    public NodeDef nodeById(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
    }
}

package com.agentflow.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * 工作流 DSL 全量定义：画布与引擎共享的唯一事实源。
 * layout 仅承载画布坐标（nodeId → {x,y}），引擎不读取；
 * 放在 DSL 里而不是另建表，是为了"画布保存一次 = 一个可回放的快照"，回滚定义时布局一起回滚。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(String name, TriggerDef trigger, List<NodeDef> nodes, List<EdgeDef> edges,
                                 Map<String, Object> layout) {

    public WorkflowDefinition {
        if (layout == null) {
            layout = Map.of();
        }
    }

    public WorkflowDefinition(String name, TriggerDef trigger, List<NodeDef> nodes, List<EdgeDef> edges) {
        this(name, trigger, nodes, edges, Map.of());
    }

    public NodeDef nodeById(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
    }
}

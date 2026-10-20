package com.agentflow.engine.dag;

import com.agentflow.engine.model.EdgeDef;
import com.agentflow.engine.model.NodeDef;
import com.agentflow.engine.model.WorkflowDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * 保存时静态校验 + 拓扑排序（唯一实现，引擎与 API 层共用，禁止旁路）。
 * 校验项：节点非空、id 唯一非空、类型已注册、边端点存在、无自环无重复边、无环（Kahn）。
 */
public final class DagValidator {

    public record ValidationResult(boolean valid, List<String> errors, List<String> topoOrder) {

        static ValidationResult ok(List<String> order) {
            return new ValidationResult(true, List.of(), order);
        }

        static ValidationResult bad(List<String> errors) {
            return new ValidationResult(false, errors, List.of());
        }
    }

    private DagValidator() {
    }

    public static ValidationResult validate(WorkflowDefinition def, Set<String> knownTypes) {
        List<String> errors = new ArrayList<>();
        if (def == null || def.nodes() == null || def.nodes().isEmpty()) {
            return ValidationResult.bad(List.of("nodes 不能为空"));
        }

        Set<String> ids = new HashSet<>();
        for (NodeDef n : def.nodes()) {
            if (n.id() == null || n.id().isBlank()) {
                errors.add("存在空节点 id");
                continue;
            }
            if (!ids.add(n.id())) {
                errors.add("节点 id 重复: " + n.id());
            }
            if (n.type() == null || !knownTypes.contains(n.type())) {
                errors.add("节点 " + n.id() + " 类型未注册: " + n.type());
            }
        }

        List<EdgeDef> edges = def.edges() == null ? List.of() : def.edges();
        Set<String> seenEdges = new HashSet<>();
        for (EdgeDef e : edges) {
            if (!ids.contains(e.from()) || !ids.contains(e.to())) {
                errors.add("边引用了不存在的节点: " + e.from() + " -> " + e.to());
            }
            if (e.from() != null && e.from().equals(e.to())) {
                errors.add("禁止自环: " + e.from());
            }
            if (!seenEdges.add(e.from() + "->" + e.to())) {
                errors.add("重复边: " + e.from() + " -> " + e.to());
            }
        }
        if (!errors.isEmpty()) {
            return ValidationResult.bad(errors);
        }

        // Kahn 拓扑排序（同层按 id 字典序出队，保证执行顺序确定性）
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        ids.forEach(id -> inDegree.put(id, 0));
        for (EdgeDef e : edges) {
            adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            inDegree.merge(e.to(), 1, Integer::sum);
        }
        // 同层按 id 字典序出队（PriorityQueue 自然序），保证执行顺序确定性
        Queue<String> queue = new PriorityQueue<>(inDegree.entrySet().stream()
                .filter(en -> en.getValue() == 0).map(Map.Entry::getKey).toList());
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            order.add(cur);
            for (String next : adj.getOrDefault(cur, List.of())) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (order.size() != ids.size()) {
            return ValidationResult.bad(List.of("检测到环，无法拓扑排序（涉及节点数 " + (ids.size() - order.size()) + "）"));
        }
        return ValidationResult.ok(List.copyOf(order));
    }
}

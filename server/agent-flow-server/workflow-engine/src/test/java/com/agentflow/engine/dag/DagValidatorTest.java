package com.agentflow.engine.dag;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.engine.dag.DagValidator.ValidationResult;
import com.agentflow.engine.model.EdgeDef;
import com.agentflow.engine.model.NodeDef;
import com.agentflow.engine.model.TriggerDef;
import com.agentflow.engine.model.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DagValidatorTest {

    private static final Set<String> TYPES = Set.of("a.type", "b.type");

    private static WorkflowDefinition def(List<NodeDef> nodes, List<EdgeDef> edges) {
        return new WorkflowDefinition("t", TriggerDef.manual(), nodes, edges);
    }

    @Test
    void happyChainProducesTopoOrder() {
        WorkflowDefinition d = def(
                List.of(new NodeDef("n1", "a.type", Map.of()), new NodeDef("n2", "b.type", Map.of()),
                        new NodeDef("n3", "a.type", Map.of())),
                List.of(new EdgeDef("n1", "n2"), new EdgeDef("n2", "n3")));
        ValidationResult r = DagValidator.validate(d, TYPES);
        assertThat(r.valid()).isTrue();
        assertThat(r.topoOrder()).containsExactly("n1", "n2", "n3");
    }

    @Test
    void cycleIsDetectedAndRejected() {
        WorkflowDefinition d = def(
                List.of(new NodeDef("x", "a.type", Map.of()), new NodeDef("y", "b.type", Map.of())),
                List.of(new EdgeDef("x", "y"), new EdgeDef("y", "x")));
        ValidationResult r = DagValidator.validate(d, TYPES);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("环"));
    }

    @Test
    void unknownTypeAndDuplicateIdReported() {
        WorkflowDefinition d = def(
                List.of(new NodeDef("n1", "nope.type", Map.of()), new NodeDef("n1", "a.type", Map.of())),
                List.of());
        ValidationResult r = DagValidator.validate(d, TYPES);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("类型未注册"));
        assertThat(r.errors()).anyMatch(e -> e.contains("id 重复"));
    }

    @Test
    void edgeToMissingNodeAndSelfLoopRejected() {
        WorkflowDefinition d = def(
                List.of(new NodeDef("n1", "a.type", Map.of())),
                List.of(new EdgeDef("n1", "ghost"), new EdgeDef("n1", "n1")));
        ValidationResult r = DagValidator.validate(d, TYPES);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("不存在的节点"));
        assertThat(r.errors()).anyMatch(e -> e.contains("自环"));
    }

    @Test
    void emptyNodesRejected() {
        assertThat(DagValidator.validate(def(List.of(), List.of()), TYPES).valid()).isFalse();
    }
}

package com.agentflow.engine.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NodeExecutorContractTest {

    private final NodeExecutor minimal = new NodeExecutor() {
        @Override
        public String type() {
            return "test.minimal";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            return NodeOutput.ok(Map.of("echo", input.paramAsString("k", "v")));
        }
    };

    @Test
    void defaultsArePinnedToContract() {
        assertThat(minimal.retryPolicy().maxAttempts()).isEqualTo(2);
        assertThat(minimal.retryPolicy().firstBackoffMs()).isEqualTo(500L);
        assertThat(minimal.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(minimal.degrade(new NodeInput(Map.of(), Map.of()), new RuntimeException("x")))
                .isEqualTo(Optional.empty());
    }

    @Test
    void nodeInputReadsParamsWithFallback() {
        NodeInput in = new NodeInput(Map.of("url", "http://a"), Map.of());
        assertThat(in.paramAsString("url", "default")).isEqualTo("http://a");
        assertThat(in.paramAsString("missing", "default")).isEqualTo("default");
    }

    @Test
    void degradedOutputCarriesVisibleFlag() {
        NodeOutput out = NodeOutput.degraded(Map.of(), "上游拒绝连接");
        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason()).contains("拒绝");
    }
}

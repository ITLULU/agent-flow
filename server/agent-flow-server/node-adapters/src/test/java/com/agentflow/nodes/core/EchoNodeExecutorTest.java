package com.agentflow.nodes.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EchoNodeExecutorTest {

    private final EchoNodeExecutor executor = new EchoNodeExecutor();

    @Test
    void echoesMessageAndPropagatesTraceId() {
        ExecutionContext ctx = new ExecutionContext("t-123", 1L, 2L);
        NodeOutput out = executor.execute(new NodeInput(Map.of("message", "hello"), Map.of()), ctx);
        assertThat(out.degraded()).isFalse();
        assertThat(out.data()).containsEntry("echo", "hello");
        assertThat(out.data()).containsEntry("traceId", "t-123");
    }

    @Test
    void missingMessageYieldsEmptyEchoNotError() {
        NodeOutput out = executor.execute(new NodeInput(Map.of(), Map.of()),
                new ExecutionContext("t", 1L, 1L));
        assertThat(out.data()).containsEntry("echo", "");
    }

    @Test
    void declaresNoRetryAndShortTimeout() {
        assertThat(executor.retryPolicy().maxAttempts()).isZero();
        assertThat(executor.timeout().toSeconds()).isEqualTo(5);
    }
}

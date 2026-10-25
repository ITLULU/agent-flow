package com.agentflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.model.EdgeDef;
import com.agentflow.engine.model.ExecutionResult;
import com.agentflow.engine.model.ExecutionStatus;
import com.agentflow.engine.model.NodeDef;
import com.agentflow.engine.model.NodeRunResult;
import com.agentflow.engine.model.NodeStatus;
import com.agentflow.engine.model.TriggerDef;
import com.agentflow.engine.model.WorkflowDefinition;
import com.agentflow.engine.port.ExecutionLogStore;
import com.agentflow.engine.registry.NodeRegistry;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 引擎核心语义钉死测试：重试次数、降级续跑、超时、不可重试分类、上游传递、非法定义拒绝。 */
class WorkflowEngineSemanticsTest {

    /** 记录型 store，验证落库调用序列。 */
    private final List<NodeRunResult> loggedNodes = new ArrayList<>();
    private final List<String> finishedStatuses = new ArrayList<>();
    private final ExecutionLogStore recordingStore = new ExecutionLogStore() {
        @Override
        public long createExecution(long workflowId, String traceId) {
            return 99L;
        }

        @Override
        public void appendNodeLog(long executionId, NodeRunResult result) {
            loggedNodes.add(result);
        }

        @Override
        public void finish(long executionId, String status) {
            finishedStatuses.add(status);
        }
    };

    private NodeRegistry registry;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry();
        engine = new WorkflowEngine(registry, recordingStore);
    }

    // ---- 测试节点池 ----

    static class UpstreamReaderExecutor implements NodeExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String type() {
            return "t.read-upstream";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            Object first = input.upstream().get("n1");
            return NodeOutput.ok(Map.of("sawN1", String.valueOf(first != null), "traceId", ctx.traceId()));
        }

        @Override
        public RetryPolicy retryPolicy() {
            return RetryPolicy.noRetry();
        }

        @Override
        public Duration timeout() {
            return Duration.ofSeconds(5);
        }
    }

    /** 永远抛 UPSTREAM_TIMEOUT，可降级：验证 重试耗尽→DEGRADED 续跑。 */
    static class FlakyDegradableExecutor implements NodeExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String type() {
            return "t.flaky-degradable";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "模拟超时");
        }

        @Override
        public RetryPolicy retryPolicy() {
            return new RetryPolicy(2, 1L); // 2 次重试、1ms 退避，保持测试快
        }

        @Override
        public Duration timeout() {
            return Duration.ofSeconds(5);
        }

        @Override
        public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
            return Optional.of(NodeOutput.degraded(Map.of("fallback", "占位内容"), "上游超时降级: " + lastError.getMessage()));
        }
    }

    /** 抛不可重试的入参错误且不降级：验证 只执行 1 次 + 执行直接 FAILED。 */
    static class FatalNonRetryableExecutor implements NodeExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String type() {
            return "t.fatal";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            throw new ApiException(ErrorCode.BAD_REQUEST, "参数错误");
        }

        @Override
        public RetryPolicy retryPolicy() {
            return new RetryPolicy(2, 1L);
        }
    }

    /** 熔断快速失败：与入参错误同属"重试无意义"，验证不会对 C0004 再打三轮下游。 */
    static class CircuitOpenExecutor implements NodeExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String type() {
            return "t.circuit-open";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "依赖 llm 熔断中");
        }

        @Override
        public RetryPolicy retryPolicy() {
            return new RetryPolicy(2, 1L);
        }
    }

    /** 不抛异常但自报 degraded：验证成功路径也认这个标志（改之前引擎只看 lastError）。 */
    static class PartialResultExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "t.partial";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            return NodeOutput.degraded(Map.of("count", 1), "四个来源只活了一个");
        }

        @Override
        public RetryPolicy retryPolicy() {
            return RetryPolicy.noRetry();
        }
    }

    /** sleep 超过自身 timeout：验证引擎层 orTimeout 生效。 */
    static class SlowExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "t.slow";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NodeOutput.ok(Map.of());
        }

        @Override
        public RetryPolicy retryPolicy() {
            return RetryPolicy.noRetry();
        }

        @Override
        public Duration timeout() {
            return Duration.ofMillis(100);
        }
    }

    private static WorkflowDefinition chain(NodeDef... nodes) {
        List<EdgeDef> edges = new ArrayList<>();
        for (int i = 0; i < nodes.length - 1; i++) {
            edges.add(new EdgeDef(nodes[i].id(), nodes[i + 1].id()));
        }
        return new WorkflowDefinition("semantics", TriggerDef.manual(), List.of(nodes), edges);
    }

    // ---- 断言 ----

    @Test
    void retryExhaustedThenDegradeContinuesAsDegraded() {
        FlakyDegradableExecutor flaky = new FlakyDegradableExecutor();
        UpstreamReaderExecutor downstream = new UpstreamReaderExecutor();
        registry.register(flaky);
        registry.register(downstream);

        ExecutionResult r = engine.run(99L, 1L,
                chain(new NodeDef("n1", "t.flaky-degradable", Map.of()),
                        new NodeDef("n2", "t.read-upstream", Map.of())), "trace-1");

        assertThat(flaky.calls.get()).isEqualTo(3); // 1 + 2 重试
        assertThat(r.status()).isEqualTo(ExecutionStatus.SUCCESS_WITH_DEGRADED);
        assertThat(r.nodeResults()).extracting(NodeRunResult::status)
                .containsExactly(NodeStatus.DEGRADED, NodeStatus.SUCCESS);
        // 降级输出照样进入上游上下文，下游确实拿到 n1 的键
        assertThat(loggedNodes.get(1).output()).containsEntry("sawN1", "true");
        assertThat(finishedStatuses).containsExactly("SUCCESS_WITH_DEGRADED");
    }

    /** 节点没抛异常但自报降级：也必须记 DEGRADED 并把整次执行升到 SUCCESS_WITH_DEGRADED。 */
    @Test
    void selfReportedDegradationOnSuccessPathIsRecordedDegraded() {
        registry.register(new PartialResultExecutor());

        ExecutionResult r = engine.run(99L, 1L,
                chain(new NodeDef("n1", "t.partial", Map.of())), "trace-partial");

        assertThat(r.nodeResults().get(0).status()).isEqualTo(NodeStatus.DEGRADED);
        assertThat(r.nodeResults().get(0).reason()).contains("只活了一个");
        assertThat(r.status()).isEqualTo(ExecutionStatus.SUCCESS_WITH_DEGRADED);
    }

    @Test
    void nonRetryableFailureStopsAfterFirstAttemptAndFailsExecution() {
        FatalNonRetryableExecutor fatal = new FatalNonRetryableExecutor();
        registry.register(fatal);

        ExecutionResult r = engine.run(99L, 1L,
                chain(new NodeDef("n1", "t.fatal", Map.of())), "trace-2");

        assertThat(fatal.calls.get()).isEqualTo(1); // 不浪费重试
        assertThat(r.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(r.nodeResults().get(0).status()).isEqualTo(NodeStatus.FAILED);
        assertThat(r.nodeResults().get(0).reason()).contains("参数错误");
        assertThat(finishedStatuses).containsExactly("FAILED");
    }

    @Test
    void circuitOpenFailureIsNotRetriedAndFailsFast() {
        CircuitOpenExecutor breaker = new CircuitOpenExecutor();
        registry.register(breaker);

        ExecutionResult r = engine.run(99L, 1L,
                chain(new NodeDef("n1", "t.circuit-open", Map.of())), "trace-2b");

        assertThat(breaker.calls.get()).isEqualTo(1);
        assertThat(r.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(r.nodeResults().get(0).reason()).contains("熔断");
    }

    @Test
    void nodeTimeoutIsEnforcedByEngine() {
        registry.register(new SlowExecutor());
        ExecutionResult r = engine.run(99L, 1L,
                chain(new NodeDef("n1", "t.slow", Map.of())), "trace-3");
        assertThat(r.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(r.nodeResults().get(0).durationMs()).isBetween(90L, 1500L); // 100ms 超时生效，未等 2s
        assertThat(r.nodeResults().get(0).reason()).contains("Timeout");
    }

    @Test
    void traceIdPropagatesIntoNodeExecutionContext() {
        UpstreamReaderExecutor reader = new UpstreamReaderExecutor();
        registry.register(reader);
        ExecutionResult r = engine.run(99L, 1L,
                chain(new NodeDef("n1", "t.read-upstream", Map.of())), "trace-42");
        assertThat(r.nodeResults().get(0).output()).containsEntry("traceId", "trace-42");
    }

    @Test
    void invalidDefinitionThrowsBeforeAnyNodeRuns() {
        assertThatThrownBy(() -> engine.run(99L, 1L,
                chain(new NodeDef("n1", "ghost.type", Map.of())), "trace-5"))
                .isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.errorCode()).isEqualTo(ErrorCode.WORKFLOW_INVALID));
        assertThat(loggedNodes).isEmpty(); // 非法定义不留半截执行
    }
}

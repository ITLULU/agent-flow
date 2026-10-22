package com.agentflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 评审 P6 #7：引擎超时后节点仍在跑 ⇒ 同一篇草稿发两次、同一张图扣两次费。
 * 钉两件事：① 放弃等待时必须中断在跑的任务；② 有外部副作用的节点在"结果不可知"后不得重试。
 */
class WorkflowEngineSideEffectTest {

    private final List<NodeRunResult> logged = new ArrayList<>();
    private final ExecutionLogStore store = new ExecutionLogStore() {
        @Override
        public long createExecution(long workflowId, String traceId) {
            return 7L;
        }

        @Override
        public void appendNodeLog(long executionId, NodeRunResult result) {
            logged.add(result);
        }

        @Override
        public void finish(long executionId, String status) {
            // 本类只关心中断与重试语义，终态由 ExecutionResult 断言
        }
    };

    private NodeRegistry registry;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry();
        engine = new WorkflowEngine(registry, store);
    }

    /** sleep 到 400ms 才算"副作用真的落下去了"；100ms 就被引擎判超时。 */
    static class SlowEffectExecutor implements NodeExecutor {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger landedEffects = new AtomicInteger();
        final CountDownLatch interrupted = new CountDownLatch(1);
        private final boolean sideEffects;

        SlowEffectExecutor(boolean sideEffects) {
            this.sideEffects = sideEffects;
        }

        @Override
        public String type() {
            return sideEffects ? "t.effect" : "t.readonly";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("节点任务被中断，副作用未落地", e);
            }
            landedEffects.incrementAndGet();
            return NodeOutput.ok(Map.of());
        }

        @Override
        public boolean hasSideEffects() {
            return sideEffects;
        }

        @Override
        public RetryPolicy retryPolicy() {
            return new RetryPolicy(2, 1L);
        }

        @Override
        public Duration timeout() {
            return Duration.ofMillis(100);
        }
    }

    private static WorkflowDefinition oneNode(String type) {
        return new WorkflowDefinition("side-effect", TriggerDef.manual(),
                List.of(new NodeDef("n1", type, Map.of())), List.of());
    }

    private ExecutionResult run(SlowEffectExecutor node) {
        registry.register(node);
        return engine.run(7L, 1L, oneNode(node.type()), "trace-se-1");
    }

    @Test
    void sideEffectNodeIsNotRetriedOnceOutcomeBecomesUnknown() throws Exception {
        SlowEffectExecutor node = new SlowEffectExecutor(true);
        ExecutionResult r = run(node);

        assertThat(node.calls.get()).isEqualTo(1); // 关键：超时后不再补刀，避免第二次发布
        assertThat(r.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(r.nodeResults().get(0).status()).isEqualTo(NodeStatus.FAILED);
        assertThat(r.nodeResults().get(0).reason())
                .contains("Timeout")
                .contains(WorkflowEngine.SIDE_EFFECT_NOTE);
        assertThat(node.interrupted.await(2, TimeUnit.SECONDS)).as("僵尸任务必须被中断").isTrue();
        assertThat(node.landedEffects.get()).as("中断早于副作用落地").isZero();
    }

    @Test
    void readOnlyNodeKeepsItsRetryBudgetAfterTimeout() throws Exception {
        SlowEffectExecutor node = new SlowEffectExecutor(false);
        ExecutionResult r = run(node);

        assertThat(node.calls.get()).isEqualTo(3); // 1 + 2 重试：读取类节点没扣费风险，语义不变
        assertThat(r.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(r.nodeResults().get(0).reason()).doesNotContain(WorkflowEngine.SIDE_EFFECT_NOTE);
        assertThat(node.interrupted.await(2, TimeUnit.SECONDS)).as("每次放弃等待都中断").isTrue();
        assertThat(node.landedEffects.get()).isZero();
    }

    @Test
    void interruptReachesTheRunningTaskNotJustTheFuture() throws Exception {
        // orTimeout 只让 future 变异常，任务本体还在虚拟线程上；这条断言区分"看起来处理了"和"真的停了"
        SlowEffectExecutor node = new SlowEffectExecutor(true);
        run(node);
        assertThat(node.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(node.calls.get()).isEqualTo(1);
        Thread.sleep(600); // 给未被中断的僵尸足够时间跑完
        assertThat(node.landedEffects.get()).as("超时后绝不允许有副作用悄悄落地").isZero();
    }
}

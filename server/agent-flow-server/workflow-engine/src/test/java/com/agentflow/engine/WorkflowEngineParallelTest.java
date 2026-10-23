package com.agentflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 并行分支语义钉死测试（评审台账"并行支路"）：
 * 同层兄弟必须真并发（不是"看起来能跑"）、汇合点能读到两条支路的输出、
 * 支路失败要立刻止损且不放行下游、结果顺序不随完成顺序漂移、并行宽度上限真的生效。
 */
class WorkflowEngineParallelTest {

    private final List<NodeRunResult> loggedNodes = new ArrayList<>();
    private final List<String> finishedStatuses = new ArrayList<>();
    private final ExecutionLogStore recordingStore = new ExecutionLogStore() {
        @Override
        public long createExecution(long workflowId, String traceId) {
            return 99L;
        }

        @Override
        public void appendNodeLog(long executionId, NodeRunResult result) {
            synchronized (loggedNodes) {
                loggedNodes.add(result);
            }
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

    // ---- 菱形 DAG：n1 → (a, b) → n4 ----

    /** 两条支路在屏障上会合：串行执行会等不到对方而超时，屏障过了才算真并发。 */
    static class BarrierBranchExecutor implements NodeExecutor {
        final CyclicBarrier barrier;
        final AtomicInteger calls = new AtomicInteger();

        BarrierBranchExecutor(CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public String type() {
            return "t.barrier-branch";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            try {
                // 2s 上限：串行时这里必然 BrokenBarrierException/Timeout，节点判失败 ⇒ 测试红
                barrier.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "屏障等待被中断");
            } catch (Exception e) {
                throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "支路未并发：屏障等不到另一条支路 " + e);
            }
            return NodeOutput.ok(Map.of("branch", "done"));
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

    /** 汇入节点：读取两条支路的输出，证明"并发之后上游上下文仍完整"。 */
    static class JoinExecutor implements NodeExecutor {
        final AtomicInteger calls = new AtomicInteger();
        volatile List<String> sawKeys = List.of();

        @Override
        public String type() {
            return "t.join";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            sawKeys = List.copyOf(input.upstream().keySet());
            return NodeOutput.ok(Map.of("joined", sawKeys.size()));
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

    /** 多条同层支路共用的并发计数器。 */
    static class SleepState {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger live = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
    }

    /** sleep 型节点：记录"进入 execute 时的并发峰值"，用于证明并行宽度上限生效。 */
    static class SleepingExecutor implements NodeExecutor {
        private final String type;
        private final SleepState state;

        SleepingExecutor(String type, SleepState state) {
            this.type = type;
            this.state = state;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            state.calls.incrementAndGet();
            state.peak.accumulateAndGet(state.live.incrementAndGet(), Math::max);
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                state.live.decrementAndGet();
            }
            return NodeOutput.ok(Map.of("ok", "true"));
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

    /** 硬失败支路：不可重试、无降级，用来验证止损。 */
    static class FailingBranchExecutor implements NodeExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String type() {
            return "t.fail-branch";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            throw new ApiException(ErrorCode.BAD_REQUEST, "支路参数错误");
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

    /** 慢成功支路：失败已经发生它还在跑，用来验证"同层兄弟不被抢占取消，但下游绝不放行"。 */
    static class SlowOkBranchExecutor implements NodeExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String type() {
            return "t.slow-ok-branch";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NodeOutput.ok(Map.of("slow", true));
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

    private static WorkflowDefinition diamond(NodeDef... nodes) {
        // n1 → a,b → n4：节点顺序故意打乱，拓扑序由校验器给出
        return new WorkflowDefinition("diamond", TriggerDef.manual(), List.of(nodes), List.of(
                new EdgeDef("n1", "a"),
                new EdgeDef("n1", "b"),
                new EdgeDef("a", "n4"),
                new EdgeDef("b", "n4")));
    }

    private static WorkflowDefinition fanOut(List<NodeDef> branches) {
        List<NodeDef> nodes = new ArrayList<>();
        nodes.add(new NodeDef("root", "t.quick-root", Map.of()));
        nodes.addAll(branches);
        List<EdgeDef> edges = new ArrayList<>();
        for (NodeDef b : branches) {
            edges.add(new EdgeDef("root", b.id()));
        }
        return new WorkflowDefinition("fanout", TriggerDef.manual(), nodes, edges);
    }

    /** root 快速通过，让 4 条支路真正落在同一层。 */
    static class RootExecutor implements NodeExecutor {
        @Override
        public String type() {
            return "t.quick-root";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            return NodeOutput.ok(Map.of("root", true));
        }

        @Override
        public RetryPolicy retryPolicy() {
            return RetryPolicy.noRetry();
        }
    }

    // ---- 断言 ----

    @Test
    void siblingBranchesRunConcurrentlyAndJoinSeesBothUpstreams() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        BarrierBranchExecutor branch = new BarrierBranchExecutor(barrier);
        JoinExecutor join = new JoinExecutor();
        registry.register(branch);
        registry.register(new RootExecutor());
        registry.register(join);

        long start = System.nanoTime();
        ExecutionResult r = engine.run(99L, 1L, diamond(
                new NodeDef("n1", "t.quick-root", Map.of()),
                new NodeDef("a", "t.barrier-branch", Map.of()),
                new NodeDef("b", "t.barrier-branch", Map.of()),
                new NodeDef("n4", "t.join", Map.of())), "p-1");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(branch.calls.get()).isEqualTo(2);
        // 屏障过了 ⇒ 两条支路同时在跑；串行执行时这里必然 FAILED
        assertThat(r.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(r.nodeResults()).extracting(NodeRunResult::status)
                .containsExactly(NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SUCCESS);
        // 拓扑序 n1,a,b,n4 —— 结果顺序不随完成顺序漂移
        assertThat(r.nodeResults()).extracting(NodeRunResult::nodeId).containsExactly("n1", "a", "b", "n4");
        assertThat(join.sawKeys).containsExactlyInAnyOrder("n1", "a", "b");
        assertThat(elapsedMs).isLessThan(2000L);
        assertThat(finishedStatuses).containsExactly("SUCCESS");
    }

    @Test
    void failedBranchShortCircuitsDownstreamWithoutDoubleFinish() {
        FailingBranchExecutor failing = new FailingBranchExecutor();
        SlowOkBranchExecutor slow = new SlowOkBranchExecutor();
        JoinExecutor join = new JoinExecutor();
        registry.register(new RootExecutor());
        registry.register(failing);
        registry.register(slow);
        registry.register(join);

        ExecutionResult r = engine.run(99L, 1L, diamond(
                new NodeDef("n1", "t.quick-root", Map.of()),
                new NodeDef("a", "t.fail-branch", Map.of()),
                new NodeDef("b", "t.slow-ok-branch", Map.of()),
                new NodeDef("n4", "t.join", Map.of())), "p-2");

        assertThat(r.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(r.nodeResults()).extracting(NodeRunResult::nodeId).containsExactly("n1", "a", "b");
        assertThat(join.calls.get()).isZero(); // 带病外发的路径被掐死在汇合点
        // 同层兄弟不抢占取消：慢支路跑完，但它的输出不会喂给下游
        assertThat(slow.calls.get()).isEqualTo(1);
        assertThat(finishedStatuses).containsExactly("FAILED"); // 只收尾一次
    }

    @Test
    void parallelWidthCapIsEnforced() {
        SleepState state = new SleepState();
        registry.register(new RootExecutor());
        for (String t : List.of("t.b1", "t.b2", "t.b3", "t.b4")) {
            registry.register(new SleepingExecutor(t, state));
        }
        WorkflowDefinition def = fanOut(List.of(
                new NodeDef("b1", "t.b1", Map.of()),
                new NodeDef("b2", "t.b2", Map.of()),
                new NodeDef("b3", "t.b3", Map.of()),
                new NodeDef("b4", "t.b4", Map.of())));

        ExecutionResult r = new WorkflowEngine(registry, recordingStore, 2).run(99L, 1L, def, "p-3");

        assertThat(r.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(state.calls.get()).isEqualTo(4);
        assertThat(state.peak.get()).isEqualTo(2); // 上限不是摆设：4 条同层支路只允许 2 条同时在跑

        state.peak.set(0);
        ExecutionResult wide = engine.run(99L, 1L, def, "p-3b"); // 默认宽度 8
        assertThat(wide.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(state.peak.get()).isEqualTo(4); // 对照组：不封顶时确实 4 条并发，上一条断言不是因为串行才成立
    }

    /**
     * 反向对照（防止并发断言假绿）：同一批 fixture 在宽度=1 时屏障等不到对方，必须判失败。
     * 若哪天有人把分层并发改回串行，上面那条"真并发"测试会绿，这条会红。
     */
    @Test
    void serialWidthCannotCrossTheBranchBarrier() {
        registry.register(new RootExecutor());
        registry.register(new BarrierBranchExecutor(new CyclicBarrier(2)));
        JoinExecutor join = new JoinExecutor();
        registry.register(join);

        ExecutionResult r = new WorkflowEngine(registry, recordingStore, 1).run(99L, 1L, diamond(
                new NodeDef("n1", "t.quick-root", Map.of()),
                new NodeDef("a", "t.barrier-branch", Map.of()),
                new NodeDef("b", "t.barrier-branch", Map.of()),
                new NodeDef("n4", "t.join", Map.of())), "p-5");

        assertThat(r.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(r.nodeResults()).filteredOn(x -> x.status() == NodeStatus.FAILED)
                .extracting(NodeRunResult::reason).allSatisfy(reason ->
                        assertThat(reason).contains("支路未并发"));
        assertThat(join.calls.get()).isZero();
    }

    @Test
    void degradedBranchStillFeedsJoinAndMarksExecutionDegraded() {        registry.register(new RootExecutor());
        registry.register(new DegradingBranchExecutor());
        registry.register(new BarrierBranchExecutor(new CyclicBarrier(1))); // 另一条支路正常通过
        JoinExecutor join = new JoinExecutor();
        registry.register(join);

        ExecutionResult r = engine.run(99L, 1L, diamond(
                new NodeDef("n1", "t.quick-root", Map.of()),
                new NodeDef("a", "t.degrade-branch", Map.of()),
                new NodeDef("b", "t.barrier-branch", Map.of()),
                new NodeDef("n4", "t.join", Map.of())), "p-4");

        assertThat(r.status()).isEqualTo(ExecutionStatus.SUCCESS_WITH_DEGRADED);
        assertThat(r.nodeResults()).extracting(NodeRunResult::status)
                .containsExactly(NodeStatus.SUCCESS, NodeStatus.DEGRADED, NodeStatus.SUCCESS, NodeStatus.SUCCESS);
        // 降级输出照样进上游上下文：并行版与串行版（SemanticsTest）在这一点上语义一致
        assertThat(join.sawKeys).containsExactlyInAnyOrder("n1", "a", "b");
        assertThat(finishedStatuses).containsExactly("SUCCESS_WITH_DEGRADED");
    }

    /** 必失败但可降级：验证并行模式下 DEGRADED 续跑语义与串行版一致。 */
    static class DegradingBranchExecutor implements NodeExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String type() {
            return "t.degrade-branch";
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            calls.incrementAndGet();
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "支路超时");
        }

        @Override
        public RetryPolicy retryPolicy() {
            return new RetryPolicy(1, 1L);
        }

        @Override
        public Duration timeout() {
            return Duration.ofSeconds(5);
        }

        @Override
        public java.util.Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
            return java.util.Optional.of(NodeOutput.degraded(Map.of("placeholder", "占位"), "支路降级"));
        }
    }
}

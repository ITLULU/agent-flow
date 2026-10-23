package com.agentflow.server.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.WorkflowEngine;
import com.agentflow.engine.model.ExecutionResult;
import com.agentflow.engine.model.ExecutionStatus;
import com.agentflow.engine.model.NodeRunResult;
import com.agentflow.engine.model.WorkflowDefinition;
import com.agentflow.engine.port.ExecutionLogStore;
import com.agentflow.server.config.ExecProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 执行闸门的容量语义（评审 P7-3 #10）：
 * 排队上限必须同步拒绝而不是无上限静默积压；等槽超时必须把执行落 FAILED，不能留下永远 RUNNING 的记录。
 */
class ExecutionManagerCapacityTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
    }

    @Test
    void queueFullIsRejectedSynchronouslyWithoutExecutionRecord() throws Exception {
        RecordingStore store = new RecordingStore();
        ExecutionManager manager = manager(store, new ExecProperties(1, Duration.ofSeconds(5), 0, null));

        manager.submit(1L, 7L, null, "t1");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> manager.submit(1L, 7L, null, "t2"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));

        // 拒绝发生在建执行记录之前：不留 RUNNING 僵尸，队列深度的瞬时增量也要归零
        assertThat(store.created.get()).isEqualTo(1);
        assertThat(registry.get("agentflow.exec.slots.queued").gauge().value()).isZero();
        assertThat(registry.get("agentflow.execution.rejected").tag("reason", "queue_full")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void waitersUpToMaxQueuedAreAdmittedAndBeyondAreRejected() throws Exception {
        RecordingStore store = new RecordingStore();
        ExecutionManager manager = manager(store, new ExecProperties(1, Duration.ofSeconds(5), 1, null));

        manager.submit(1L, 7L, null, "t1");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        long second = manager.submit(1L, 7L, null, "t2");
        assertThat(registry.get("agentflow.exec.slots.queued").gauge().value()).isEqualTo(1.0);

        assertThatThrownBy(() -> manager.submit(1L, 7L, null, "t3"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        assertThat(store.created.get()).isEqualTo(2);

        release.countDown();
        // 第二个排到槽后应正常跑完，证明上限只拦增量、不饿死已入队者
        awaitFinish(store, second, "SUCCESS");
    }

    @Test
    void waitingPastAcquireTimeoutFinishesExecutionAsFailed() throws Exception {
        RecordingStore store = new RecordingStore();
        ExecutionManager manager = manager(store, new ExecProperties(1, Duration.ofMillis(150), 10, null));

        manager.submit(1L, 7L, null, "t1");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        long second = manager.submit(1L, 7L, null, "t2");

        awaitFinish(store, second, "FAILED");
        assertThat(registry.get("agentflow.execution.rejected").tag("reason", "acquire_timeout")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agentflow.exec.slots.queued").gauge().value()).isZero();
        assertThat(registry.get("agentflow.exec.slots.available").gauge().value()).isZero();
    }

    @Test
    void propertiesValidateAndDefault() {
        ExecProperties defaults = new ExecProperties(null, null, null, null);
        assertThat(defaults.maxConcurrent()).isEqualTo(16);
        assertThat(defaults.acquireTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.maxQueued()).isEqualTo(64);
        // 不启用全局配额时不许要求任何配额数字：单实例形态不该被多实例的配置卡住启动
        assertThat(defaults.globalQuota().enabled()).isFalse();

        assertThatThrownBy(() -> new ExecProperties(0, Duration.ofSeconds(1), 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-concurrent");
        assertThatThrownBy(() -> new ExecProperties(1, Duration.ZERO, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("acquire-timeout");
        assertThatThrownBy(() -> new ExecProperties(1, Duration.ofSeconds(1), -1, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-queued");
    }

    @Test
    void globalQuotaConfigFailsFastOnSelfContradictoryLimits() {
        ExecProperties.GlobalQuota ok = new ExecProperties.GlobalQuota(true, 5, 2,
                Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofSeconds(1));
        assertThat(ok.enabled()).isTrue();

        // 启用却不给全局上限：最容易发生的"以为配了"
        assertThatThrownBy(() -> new ExecProperties.GlobalQuota(true, null, 0,
                Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-concurrent");
        assertThatThrownBy(() -> new ExecProperties.GlobalQuota(true, 5, null,
                Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("per-user-max-concurrent");
        // 单用户上限大于全局上限 ⇒ 这条配置永远不起效，写它的人以为自己有保护
        assertThatThrownBy(() -> new ExecProperties.GlobalQuota(true, 5, 8,
                Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("永不起效");
        // 续租节拍 >= TTL ⇒ 长执行的租约会先过期被别人抢走，上限被静默突破
        assertThatThrownBy(() -> new ExecProperties.GlobalQuota(true, 5, 0,
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("renew-interval");
        assertThatThrownBy(() -> new ExecProperties.GlobalQuota(true, 5, 0,
                Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserve-retry-interval");
        // 关闭时同样的荒谬数字不拦：默认形态必须零成本
        assertThat(new ExecProperties.GlobalQuota(false, null, null, null, null, null).enabled()).isFalse();
    }

    @Test
    void saturatedGlobalQuotaIsRejectedSynchronouslyWithoutExecutionRecord() {
        RecordingStore store = new RecordingStore();
        FakeQuota quota = new FakeQuota(0);
        ExecutionManager manager = manager(store, quotaProps(2, Duration.ofSeconds(5)), quota);

        assertThatThrownBy(() -> manager.submit(1L, 7L, null, "t1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));

        // 拒绝发生在拿本地槽和建记录之前：集群满了不该把本地队列和台账一起填脏
        assertThat(store.created.get()).isZero();
        assertThat(registry.get("agentflow.exec.slots.available").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("agentflow.execution.rejected").tag("reason", "global_quota")
                .counter().count()).isEqualTo(1.0);
        assertThat(quota.reserved).isEmpty();
    }

    @Test
    void executionWaitsForGlobalCapacityThenRunsAndReleasesItsLease() throws Exception {
        RecordingStore store = new RecordingStore();
        // 只读预检说有位置、真正预留时被别的实例抢先——多实例下的正常竞态，必须等而不是失败
        AtomicReference<Double> waitingWhileRefused = new AtomicReference<>();
        FakeQuota quota = new FakeQuota(10).refuseFirst(2).onRefusal(
                () -> waitingWhileRefused.set(registry.get("agentflow.exec.global.waiting").gauge().value()));
        ExecutionManager manager = manager(store, quotaProps(2, Duration.ofSeconds(5)), quota);

        long id = manager.submit(1L, 7L, null, "t1");
        release.countDown();
        awaitFinish(store, id, "SUCCESS");

        assertThat(quota.reserveCalls).isGreaterThanOrEqualTo(3);
        assertThat(quota.released).containsExactly(id);
        // 执行记录这段时间是 RUNNING 的，运维要能把"真在跑"和"在等集群名额"分开看
        assertThat(waitingWhileRefused.get()).isEqualTo(1.0);
        assertThat(registry.get("agentflow.exec.global.waiting").gauge().value()).isZero();
    }

    @Test
    void globalCapacityThatNeverFreesUpFinishesAsFailedAndReturnsLocalSlot() throws Exception {
        RecordingStore store = new RecordingStore();
        FakeQuota quota = new FakeQuota(10).refuseForever();
        ExecutionManager manager = manager(store, quotaProps(2, Duration.ofMillis(300)), quota);

        long id = manager.submit(1L, 7L, null, "t1");

        // 等不到名额也必须显式收尾：提交返回了 executionId 却永远 RUNNING 是最难查的静默失败
        awaitFinish(store, id, "FAILED");
        assertThat(registry.get("agentflow.execution.rejected").tag("reason", "global_quota_timeout")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agentflow.exec.slots.available").gauge().value()).isEqualTo(2.0);
        // 没拿到的名额不许"顺手释放"：那会删掉真正持有者的租约，把上限悄悄放大
        assertThat(quota.released).isEmpty();
    }

    private static ExecProperties quotaProps(int maxConcurrent, Duration acquireTimeout) {
        return new ExecProperties(maxConcurrent, acquireTimeout, 8, new ExecProperties.GlobalQuota(
                true, 100, 0, Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMillis(30)));
    }

    /** 假配额：只用来驱动 ExecutionManager 的三条路径（预检拒绝 / 等待成功 / 等待超时）。 */
    private static final class FakeQuota implements ExecutionQuota {

        private final int advertisedCapacity;
        private int refusals;
        private boolean refuseForever;
        private int reserveCalls;
        private Runnable onRefusal;
        private final java.util.List<Long> released = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.Set<Long> reserved = java.util.concurrent.ConcurrentHashMap.newKeySet();

        FakeQuota(int advertisedCapacity) {
            this.advertisedCapacity = advertisedCapacity;
        }

        FakeQuota refuseFirst(int times) {
            this.refusals = times;
            return this;
        }

        FakeQuota refuseForever() {
            this.refuseForever = true;
            return this;
        }

        FakeQuota onRefusal(Runnable hook) {
            this.onRefusal = hook;
            return this;
        }

        @Override
        public boolean hasCapacity(long ownerId) {
            return advertisedCapacity > 0;
        }

        @Override
        public boolean tryReserve(long executionId, long ownerId) {
            reserveCalls++;
            if (refuseForever || refusals-- > 0) {
                if (onRefusal != null) {
                    onRefusal.run(); // 让测试在"确实正在等名额"的那一刻取样
                }
                return false;
            }
            reserved.add(executionId);
            return true;
        }

        @Override
        public void release(long executionId) {
            reserved.remove(executionId);
            released.add(executionId);
        }

        @Override
        public void renewHeld() {
            // no-op
        }

        @Override
        public long reserved() {
            return reserved.size();
        }
    }

    private ExecutionManager manager(ExecutionLogStore store, ExecProperties props) {
        return manager(store, props, null);
    }

    private ExecutionManager manager(ExecutionLogStore store, ExecProperties props, ExecutionQuota quota) {
        WorkflowEngine blocking = new WorkflowEngine(null, ExecutionLogStore.NOOP) {
            @Override
            public ExecutionResult run(long executionId, long workflowId, WorkflowDefinition def, String traceId) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                store.finish(executionId, ExecutionStatus.SUCCESS.name()); // 真实引擎在 run 内部收尾
                return new ExecutionResult(executionId, ExecutionStatus.SUCCESS, List.of());
            }
        };
        return new ExecutionManager(blocking, store, registry, props, quota);
    }

    private static void awaitFinish(RecordingStore store, long executionId, String expected) throws Exception {
        for (int i = 0; i < 50 && !expected.equals(store.finishes.get(executionId)); i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(store.finishes.get(executionId)).isEqualTo(expected);
    }

    private static final class RecordingStore implements ExecutionLogStore {

        private final AtomicLong seq = new AtomicLong();
        private final AtomicLong created = new AtomicLong();
        private final Map<Long, String> finishes = new ConcurrentHashMap<>();

        @Override
        public long createExecution(long workflowId, String traceId) {
            created.incrementAndGet();
            return seq.incrementAndGet();
        }

        @Override
        public void appendNodeLog(long executionId, NodeRunResult result) {
            // no-op
        }

        @Override
        public void finish(long executionId, String status) {
            finishes.put(executionId, status);
        }
    }
}

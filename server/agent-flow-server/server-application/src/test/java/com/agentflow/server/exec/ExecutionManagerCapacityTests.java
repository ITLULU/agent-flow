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
        ExecutionManager manager = manager(store, new ExecProperties(1, Duration.ofSeconds(5), 0));

        manager.submit(1L, null, "t1");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> manager.submit(1L, null, "t2"))
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
        ExecutionManager manager = manager(store, new ExecProperties(1, Duration.ofSeconds(5), 1));

        manager.submit(1L, null, "t1");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        long second = manager.submit(1L, null, "t2");
        assertThat(registry.get("agentflow.exec.slots.queued").gauge().value()).isEqualTo(1.0);

        assertThatThrownBy(() -> manager.submit(1L, null, "t3"))
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
        ExecutionManager manager = manager(store, new ExecProperties(1, Duration.ofMillis(150), 10));

        manager.submit(1L, null, "t1");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        long second = manager.submit(1L, null, "t2");

        awaitFinish(store, second, "FAILED");
        assertThat(registry.get("agentflow.execution.rejected").tag("reason", "acquire_timeout")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agentflow.exec.slots.queued").gauge().value()).isZero();
        assertThat(registry.get("agentflow.exec.slots.available").gauge().value()).isZero();
    }

    @Test
    void propertiesValidateAndDefault() {
        ExecProperties defaults = new ExecProperties(null, null, null);
        assertThat(defaults.maxConcurrent()).isEqualTo(16);
        assertThat(defaults.acquireTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.maxQueued()).isEqualTo(64);

        assertThatThrownBy(() -> new ExecProperties(0, Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-concurrent");
        assertThatThrownBy(() -> new ExecProperties(1, Duration.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("acquire-timeout");
        assertThatThrownBy(() -> new ExecProperties(1, Duration.ofSeconds(1), -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-queued");
    }

    private ExecutionManager manager(ExecutionLogStore store, ExecProperties props) {
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
        return new ExecutionManager(blocking, store, registry, props);
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

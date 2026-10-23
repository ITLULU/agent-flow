package com.agentflow.server.exec;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.WorkflowEngine;
import com.agentflow.engine.model.ExecutionResult;
import com.agentflow.engine.model.ExecutionStatus;
import com.agentflow.engine.model.WorkflowDefinition;
import com.agentflow.engine.port.ExecutionLogStore;
import com.agentflow.server.config.ExecProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 执行调度门面：API 层只面对这里，不直接触引擎。
 * 并发口径：虚拟线程承接提交 + 信号量限制同时"正在跑"的工作流数，容量由 agentflow.exec.* 运行期配置。
 * 闸门防的是 LLM/外部依赖被自己打爆（见 IMPLEMENTATION-PLAN §9），但闸门必须可见、可弃：
 * 排队超过 max-queued 同步 429（不产生执行记录）；等待超过 acquire-timeout 则落 FAILED 并释放槽位，
 * 避免"提交成功却永远 RUNNING"的静默排队（评审 P7-3 #10）。
 * 本地信号量只看得到本进程，多实例部署时"每实例 16"= 全局 16×N（评审 P7-3 #11）；因此当
 * agentflow.exec.global-quota.enabled=true 时再加一道数据库仲裁的全集群名额：提交时只读预检满不满
 * （满则同步 429、不建记录），拿到本地槽后真正预留名额，跑完即还；等不到名额同样落 FAILED，
 * 不留永远 RUNNING 的记录。配额关闭时 quota=null，本类的行为与单机形态逐字节一致。
 * 指标口径（评审 P6 #3）：submitted/finished 给错误率与吞吐，duration 给 RT，
 * queue.wait + slots.available/queued 给饱和度，rejected{reason} 让丢弃量有账可查。
 */
@Service
@EnableConfigurationProperties(ExecProperties.class)
public class ExecutionManager {

    private static final Logger log = LoggerFactory.getLogger(ExecutionManager.class);

    private final WorkflowEngine engine;
    private final ExecutionLogStore store;
    private final MeterRegistry registry;
    private final ExecProperties props;
    @Nullable
    private final ExecutionQuota quota;
    private final Semaphore slots;
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger capacityWaiters = new AtomicInteger();
    private final ExecutorService dispatcher = Executors.newVirtualThreadPerTaskExecutor();
    private final Timer executionDuration;
    private final Timer queueWait;

    public ExecutionManager(WorkflowEngine engine, ExecutionLogStore store, MeterRegistry registry,
                            ExecProperties props, @Nullable ExecutionQuota quota) {
        this.engine = engine;
        this.store = store;
        this.registry = registry;
        this.props = props;
        this.quota = quota;
        this.slots = new Semaphore(props.maxConcurrent(), true);
        this.executionDuration = Timer.builder("agentflow.execution.duration")
                .description("一次工作流执行从拿到执行槽到收尾的耗时").register(registry);
        this.queueWait = Timer.builder("agentflow.execution.queue.wait")
                .description("提交后等待执行槽的时间——排队即饱和信号").register(registry);
        // gauge 只存引用、抓取时回调，注册放在 slots 初始化之后
        registry.gauge("agentflow.exec.slots.available", slots, s -> (double) s.availablePermits());
        registry.gauge("agentflow.exec.slots.queued", waiting, w -> (double) w.get());
        registry.gauge("agentflow.exec.slots.capacity", slots, s -> (double) props.maxConcurrent());
        // 执行记录状态 RUNNING 含"等集群名额"这一段，运维必须能把"真在跑"和"在等名额"分开看
        registry.gauge("agentflow.exec.global.waiting", capacityWaiters, w -> (double) w.get());
    }

    /** 预创建执行记录并异步跑链路，立即返回 executionId 供轮询；排队已满则同步拒绝（429）。 */
    public long submit(long workflowId, long ownerId, WorkflowDefinition def, String traceId) {
        // 全集群已经跑满时无需再收单：这里只是只读预检（不预留名额），拒绝发生在建执行记录之前
        if (quota != null && !quota.hasCapacity(ownerId)) {
            recordRejected("global_quota");
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "集群执行配额已满（在跑 " + quota.reserved() + " 条），请稍后重试");
        }
        long enqueuedAt = System.nanoTime();
        boolean hasSlot = tryAcquireNow();
        if (!hasSlot) {
            int queued = waiting.incrementAndGet();
            if (queued > props.maxQueued()) {
                waiting.decrementAndGet();
                recordRejected("queue_full");
                throw new ApiException(ErrorCode.RATE_LIMITED,
                        "执行队列已满（等待 " + (queued - 1) + "/" + props.maxQueued() + "），请稍后重试");
            }
        }
        long executionId;
        try {
            executionId = store.createExecution(workflowId, traceId);
        } catch (RuntimeException e) {
            releaseUnusedSlot(hasSlot);
            throw e;
        }
        Counter.builder("agentflow.execution.submitted").register(registry).increment();
        try {
            dispatcher.submit(() -> run(executionId, workflowId, ownerId, def, traceId, hasSlot, enqueuedAt));
        } catch (RuntimeException e) {
            releaseUnusedSlot(hasSlot);
            safeFinish(executionId);
            throw e;
        }
        return executionId;
    }

    private void run(long executionId, long workflowId, long ownerId, WorkflowDefinition def, String traceId,
                     boolean hasSlot, long enqueuedAt) {
        try {
            if (!hasSlot) {
                boolean acquired = false;
                try {
                    acquired = slots.tryAcquire(props.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    waiting.decrementAndGet();
                }
                if (!acquired) {
                    // 槽位是排队者的唯一出口：等不到就必须显式失败，否则执行记录永远是 RUNNING
                    log.warn("[traceId={}] 执行 {} 排队超过 {}ms，放弃执行", traceId, executionId,
                            props.acquireTimeout().toMillis());
                    recordRejected("acquire_timeout");
                    recordFinish(ExecutionStatus.FAILED.name());
                    safeFinish(executionId);
                    return;
                }
            }
            try {
                // 名额在拿到本地槽之后才预留：租约覆盖"真的在跑"这段时间，本地排队不占集群额度。
                // 代价是等名额时会握着一个本地槽（最长 acquire-timeout）——集群跑满时这本来就是要发生的等待。
                if (quota != null && !reserveQuota(executionId, ownerId, traceId)) {
                    recordRejected("global_quota_timeout");
                    recordFinish(ExecutionStatus.FAILED.name());
                    safeFinish(executionId);
                    return;
                }
                try {
                    queueWait.record(Duration.ofNanos(System.nanoTime() - enqueuedAt));
                    long startedAt = System.nanoTime();
                    ExecutionResult result = engine.run(executionId, workflowId, def, traceId);
                    executionDuration.record(Duration.ofNanos(System.nanoTime() - startedAt));
                    recordFinish(result.status().name());
                    log.info("[traceId={}] 执行 {} 完成: {}", traceId, executionId, result.status());
                } finally {
                    if (quota != null) {
                        quota.release(executionId);
                    }
                }
            } finally {
                slots.release();
            }
        } catch (Exception e) {
            // 引擎内部已收敛节点异常；走到这里的是定义非法/持久层故障，必须兜底收尾，禁止留 RUNNING 僵尸
            log.error("[traceId={}] 执行 {} 异常终止", traceId, executionId, e);
            recordFinish("ABORTED");
            safeFinish(executionId);
        }
    }

    /**
     * 预留集群名额：跑满就按 reserve-retry-interval 重试，最长等到 {@code exec.acquire-timeout}。
     * 等不到返回 false，由调用方把执行落 FAILED —— 和"等不到本地槽"同一口径，绝不静默悬挂。
     * 预留本身失败（配额表缺失等）会抛出，走 run 的兜底分支，不会悄悄放行。
     */
    private boolean reserveQuota(long executionId, long ownerId, String traceId) {
        long deadline = System.nanoTime() + props.acquireTimeout().toNanos();
        long retryNanos = props.globalQuota().reserveRetryInterval().toNanos();
        capacityWaiters.incrementAndGet();
        try {
            while (true) {
                if (quota.tryReserve(executionId, ownerId)) {
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    log.warn("[traceId={}] 执行 {} 等不到集群执行名额（上限 {}），放弃执行", traceId, executionId,
                            props.globalQuota().maxConcurrent());
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(retryNanos, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        } finally {
            capacityWaiters.decrementAndGet();
        }
    }

    /** 公平信号量的 tryAcquire(0)：已有排队者时不插队，且不阻塞请求线程。 */
    private boolean tryAcquireNow() {
        try {
            return slots.tryAcquire(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void releaseUnusedSlot(boolean hasSlot) {
        if (hasSlot) {
            slots.release();
        } else {
            waiting.decrementAndGet();
        }
    }

    private void recordRejected(String reason) {
        Counter.builder("agentflow.execution.rejected").tag("reason", reason).register(registry).increment();
    }

    private void recordFinish(String status) {
        Counter.builder("agentflow.execution.finished")
                .tag("status", status)
                .register(registry)
                .increment();
    }

    private void safeFinish(long executionId) {
        try {
            store.finish(executionId, ExecutionStatus.FAILED.name());
        } catch (Exception ex) {
            log.error("执行 {} 状态收尾失败", executionId, ex);
        }
    }
}

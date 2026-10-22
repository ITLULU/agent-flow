package com.agentflow.server.exec;

import com.agentflow.engine.WorkflowEngine;
import com.agentflow.engine.model.ExecutionResult;
import com.agentflow.engine.model.ExecutionStatus;
import com.agentflow.engine.model.WorkflowDefinition;
import com.agentflow.engine.port.ExecutionLogStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 执行调度门面：API 层只面对这里，不直接触引擎。
 * 并发口径：虚拟线程承接提交 + Semaphore(16) 限制同时"正在跑"的工作流数——
 * 单机 100 QPS 峰值下触发不是瓶颈（执行是异步长任务），闸门防的是 LLM/外部依赖被自己打爆（见 IMPLEMENTATION-PLAN §9）。
 * 指标口径（评审 P6 #3：闸门是暗箱就等于没有闸门）：
 * submitted/finished 给错误率与吞吐，duration 给 RT，queue.wait + slots.queued/available 给饱和度。
 */
@Service
public class ExecutionManager {

    private static final Logger log = LoggerFactory.getLogger(ExecutionManager.class);

    /** 同时执行的工作流上限（单实例口径，多实例部署时改全局配额归 P3 限流层） */
    private static final int MAX_CONCURRENT_EXECUTIONS = 16;

    private final WorkflowEngine engine;
    private final ExecutionLogStore store;
    private final MeterRegistry registry;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_EXECUTIONS, true);
    private final ExecutorService dispatcher = Executors.newVirtualThreadPerTaskExecutor();
    private final Timer executionDuration;
    private final Timer queueWait;

    public ExecutionManager(WorkflowEngine engine, ExecutionLogStore store, MeterRegistry registry) {
        this.engine = engine;
        this.store = store;
        this.registry = registry;
        this.executionDuration = Timer.builder("agentflow.execution.duration")
                .description("一次工作流执行从拿到执行槽到收尾的耗时").register(registry);
        this.queueWait = Timer.builder("agentflow.execution.queue.wait")
                .description("提交后等待执行槽的时间——排队即饱和信号").register(registry);
        // gauge 只存引用、抓取时回调，注册放在 slots 初始化之后
        registry.gauge("agentflow.exec.slots.available", slots, s -> (double) s.availablePermits());
        registry.gauge("agentflow.exec.slots.queued", slots, s -> (double) s.getQueueLength());
    }

    /** 预创建执行记录并异步跑链路，立即返回 executionId 供轮询。 */
    public long submit(long workflowId, WorkflowDefinition def, String traceId) {
        long executionId = store.createExecution(workflowId, traceId);
        long enqueuedAt = System.nanoTime();
        Counter.builder("agentflow.execution.submitted").register(registry).increment();
        dispatcher.submit(() -> {
            try {
                slots.acquire();
                queueWait.record(Duration.ofNanos(System.nanoTime() - enqueuedAt));
                try {
                    long startedAt = System.nanoTime();
                    ExecutionResult result = engine.run(executionId, workflowId, def, traceId);
                    executionDuration.record(Duration.ofNanos(System.nanoTime() - startedAt));
                    recordFinish(result.status().name());
                    log.info("[traceId={}] 执行 {} 完成: {}", traceId, executionId, result.status());
                } finally {
                    slots.release();
                }
            } catch (Exception e) {
                // 引擎内部已收敛节点异常；走到这里的是定义非法/持久层故障，必须兜底收尾，禁止留 RUNNING 僵尸
                log.error("[traceId={}] 执行 {} 异常终止", traceId, executionId, e);
                recordFinish("ABORTED");
                safeFinish(executionId);
            }
        });
        return executionId;
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

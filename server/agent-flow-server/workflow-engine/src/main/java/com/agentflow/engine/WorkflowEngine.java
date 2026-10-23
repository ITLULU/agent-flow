package com.agentflow.engine;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.trace.TraceIdUtils;
import com.agentflow.engine.dag.DagValidator;
import com.agentflow.engine.model.ExecutionResult;
import com.agentflow.engine.model.ExecutionStatus;
import com.agentflow.engine.model.NodeDef;
import com.agentflow.engine.model.NodeRunResult;
import com.agentflow.engine.model.NodeStatus;
import com.agentflow.engine.model.WorkflowDefinition;
import com.agentflow.engine.port.ExecutionLogStore;
import com.agentflow.engine.registry.NodeRegistry;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAG 执行器：拓扑序逐节点执行（v1 不做并行分支，见延期台账）。
 * 每个节点强制执行 SPI 四件套：orTimeout 超时、异常捕获分类、重试指数退避、降级显性标志。
 * 节点任务跑在虚拟线程上，traceId 手工透传（虚拟线程不继承 MDC）。
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 副作用节点超时后写进错误信息的口径：让"可能已经发出去了"变成执行日志里看得见的事实，而不是靠人猜。 */
    public static final String SIDE_EFFECT_NOTE =
            "｜副作用结果不可知：请求可能已到达对端（草稿可能已创建、费用可能已扣），已放弃重试并中断任务，请人工核对";

    private final NodeRegistry registry;
    private final ExecutionLogStore store;
    private final ExecutorService nodePool = Executors.newVirtualThreadPerTaskExecutor();

    public WorkflowEngine(NodeRegistry registry, ExecutionLogStore store) {
        this.registry = registry;
        this.store = store;
    }

    /**
     * 同步执行整条链路。executionId 由调用方预创建（API 立即拿到 id 供轮询）。
     * 定义不合法直接抛 ApiException(WORKFLOW_INVALID)，不会产生半截执行。
     */
    public ExecutionResult run(long executionId, long workflowId, WorkflowDefinition def, String traceId) {
        DagValidator.ValidationResult vr = DagValidator.validate(def, registry.knownTypes());
        if (!vr.valid()) {
            throw new ApiException(ErrorCode.WORKFLOW_INVALID, String.join("; ", vr.errors()));
        }

        Map<String, Object> upstream = new HashMap<>();
        List<NodeRunResult> results = new ArrayList<>();
        boolean anyDegraded = false;

        for (String nodeId : vr.topoOrder()) {
            NodeDef nodeDef = def.nodeById(nodeId);
            NodeExecutor executor = registry.require(nodeDef.type());
            ExecutionContext ctx = new ExecutionContext(traceId, workflowId, executionId);
            Map<String, Object> params = nodeDef.params() == null ? Map.of() : nodeDef.params();
            NodeInput input = new NodeInput(params, Map.copyOf(upstream));

            long start = System.nanoTime();
            int maxAttempts = 1 + executor.retryPolicy().maxAttempts();
            Throwable lastError = null;
            NodeOutput output = null;
            int usedAttempts = 0;
            boolean outcomeUnknown = false;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                usedAttempts = attempt;
                try {
                    output = executeOnce(executor, input, ctx, traceId);
                    lastError = null;
                    outcomeUnknown = false;
                    break;
                } catch (Throwable t) {
                    lastError = unwrap(t);
                    outcomeUnknown = executor.hasSideEffects() && isOutcomeUnknown(lastError);
                    log.warn("[traceId={}] 节点 {} 第 {} 次尝试失败: {}{}", traceId, nodeId, attempt, brief(lastError),
                            outcomeUnknown ? "（该节点有外部副作用，结果不可知 ⇒ 不再重试）" : "");
                    if (!isRetryable(lastError) || attempt == maxAttempts || outcomeUnknown) {
                        break;
                    }
                    sleepQuietly(executor.retryPolicy().firstBackoffMs() * (1L << (attempt - 1)));
                }
            }
            long durationMs = (System.nanoTime() - start) / 1_000_000L;

            if (lastError == null) {
                upstream.put(nodeId, output.data());
                NodeRunResult r = new NodeRunResult(nodeId, nodeDef.type(), NodeStatus.SUCCESS, usedAttempts, durationMs, output.data(), null);
                results.add(r);
                store.appendNodeLog(executionId, r);
            } else {
                Optional<NodeOutput> degraded = tryDegrade(executor, input, lastError);
                if (degraded.isPresent()) {
                    anyDegraded = true;
                    NodeOutput d = degraded.get();
                    upstream.put(nodeId, d.data());
                    NodeRunResult r = new NodeRunResult(nodeId, nodeDef.type(), NodeStatus.DEGRADED, usedAttempts, durationMs,
                            d.data(), d.degradeReason());
                    results.add(r);
                    store.appendNodeLog(executionId, r);
                    log.warn("[traceId={}] 节点 {} 走降级路径继续执行: {}", traceId, nodeId, d.degradeReason());
                } else {
                    NodeRunResult r = new NodeRunResult(nodeId, nodeDef.type(), NodeStatus.FAILED, usedAttempts, durationMs,
                            Map.of(), brief(lastError) + (outcomeUnknown ? SIDE_EFFECT_NOTE : ""));
                    results.add(r);
                    store.appendNodeLog(executionId, r);
                    store.finish(executionId, ExecutionStatus.FAILED.name());
                    log.error("[traceId={}] 节点 {} 重试耗尽且无降级，执行终止: {}", traceId, nodeId, brief(lastError));
                    return new ExecutionResult(executionId, ExecutionStatus.FAILED, List.copyOf(results));
                }
            }
        }

        ExecutionStatus finalStatus = anyDegraded ? ExecutionStatus.SUCCESS_WITH_DEGRADED : ExecutionStatus.SUCCESS;
        store.finish(executionId, finalStatus.name());
        return new ExecutionResult(executionId, finalStatus, List.copyOf(results));
    }

    private NodeOutput executeOnce(NodeExecutor executor, NodeInput input, ExecutionContext ctx, String traceId) {
        // orTimeout 只让 future 变异常，节点任务仍在虚拟线程上继续跑；不记线程、不中断，
        // 它就会在执行早已判为 FAILED 之后把草稿真发出去、把图真扣费（评审 P6 #7）。
        AtomicReference<Thread> worker = new AtomicReference<>();
        CompletableFuture<NodeOutput> future = CompletableFuture
                .supplyAsync(() -> {
                    worker.set(Thread.currentThread());
                    TraceIdUtils.put(traceId); // 虚拟线程不继承 MDC，手工钉入保证节点内日志可关联
                    try {
                        return executor.execute(input, ctx);
                    } finally {
                        worker.set(null);
                        TraceIdUtils.clear();
                    }
                }, nodePool)
                .orTimeout(executor.timeout().toMillis(), TimeUnit.MILLISECONDS);
        try {
            return future.join();
        } catch (RuntimeException | Error abandoned) {
            Thread running = worker.getAndSet(null);
            if (running != null) {
                running.interrupt();
            }
            throw abandoned;
        }
    }

    /**
     * "结果不可知"仅指引擎侧放弃等待的三种：等待超时、任务被中断、future 被取消。
     * 节点自己按对端错误码分类出来的失败（如微信 errcode=-1 系统繁忙）不在此列——
     * 那是"对端明确告诉你没做成"，照常重试才符合各节点已单测钉住的语义。
     */
    private boolean isOutcomeUnknown(Throwable t) {
        return t instanceof TimeoutException || t instanceof InterruptedException
                || t instanceof CancellationException;
    }

    /** 分类：入参/权限/定义类错误重试无意义，直接失败；其余（含超时、IO、UPSTREAM_*）按策略重试。 */
    private boolean isRetryable(Throwable t) {
        if (t instanceof ApiException ae) {
            return switch (ae.errorCode()) {
                case BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, WORKFLOW_INVALID,
                     DEPENDENCY_UNAVAILABLE -> false;
                default -> true;
            };
        }
        return true;
    }

    private Optional<NodeOutput> tryDegrade(NodeExecutor executor, NodeInput input, Throwable lastError) {
        try {
            return executor.degrade(input, lastError);
        } catch (Exception e) {
            log.error("节点 {} 降级路径本身异常，按失败处理", executor.type(), e);
            return Optional.empty();
        }
    }

    private Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof CompletionException || cur instanceof RuntimeException) && cur.getCause() != null
                && cur.getCause() != cur) {
            cur = cur.getCause();
            if (!(cur instanceof CompletionException) && !(cur instanceof RuntimeException)) {
                break;
            }
        }
        return cur;
    }

    private String brief(Throwable t) {
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
        return msg.length() <= 500 ? msg : msg.substring(0, 500);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

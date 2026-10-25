package com.agentflow.engine;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.trace.TraceIdUtils;
import com.agentflow.engine.dag.DagValidator;
import com.agentflow.engine.model.EdgeDef;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAG 执行器：按依赖分层，同层节点并发执行（并行宽度上限 maxParallelNodes），层末汇合。
 * 每个节点强制执行 SPI 四件套：orTimeout 超时、异常捕获分类、重试指数退避、降级显性标志。
 * 节点任务跑在虚拟线程上，traceId 手工透传（虚拟线程不继承 MDC）。
 * 失败语义：某节点重试耗尽且无降级 ⇒ 终止执行；同层兄弟不会被抢占取消（汇合点即止损线），
 * 下一层不再启动——这是"宁可少跑也不能带病外发"的显式取舍。
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 副作用节点超时后写进错误信息的口径：让"可能已经发出去了"变成执行日志里看得见的事实，而不是靠人猜。 */
    public static final String SIDE_EFFECT_NOTE =
            "｜副作用结果不可知：请求可能已到达对端（草稿可能已创建、费用可能已扣），已放弃重试并中断任务，请人工核对";

    private final NodeRegistry registry;
    private final ExecutionLogStore store;
    private final ExecutorService nodePool = Executors.newVirtualThreadPerTaskExecutor();
    private final int maxParallelNodes;

    public WorkflowEngine(NodeRegistry registry, ExecutionLogStore store) {
        this(registry, store, 8);
    }

    public WorkflowEngine(NodeRegistry registry, ExecutionLogStore store, int maxParallelNodes) {
        this.registry = registry;
        this.store = store;
        this.maxParallelNodes = Math.max(1, maxParallelNodes);
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

        Map<String, Object> upstream = new ConcurrentHashMap<>();
        List<NodeRunResult> results = Collections.synchronizedList(new ArrayList<>());
        Set<String> done = ConcurrentHashMap.newKeySet();
        Map<String, Set<String>> preds = predecessors(def);
        List<String> order = vr.topoOrder();
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean anyDegraded = new AtomicBoolean();
        Semaphore slots = new Semaphore(maxParallelNodes);

        // 分层并发：同一层（互相无依赖）的节点同时跑，层末汇合后再算下一层。
        // 之所以在层末设汇合点而不是彻底异步：分支失败要能立刻止损，汇合点就是天然的止损线，
        // 也让"上游数据已就绪"这个前提在每个节点上都可证。
        while (!aborted.get()) {
            List<String> wave = new ArrayList<>();
            for (String nodeId : order) {
                if (!done.contains(nodeId) && done.containsAll(preds.get(nodeId))) {
                    wave.add(nodeId);
                }
            }
            if (wave.isEmpty()) {
                break;
            }
            List<CompletableFuture<Void>> inFlight = new ArrayList<>();
            for (String nodeId : wave) {
                inFlight.add(CompletableFuture.runAsync(() -> {
                    try {
                        slots.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        aborted.set(true);
                        return;
                    }
                    try {
                        if (!aborted.get()) {
                            runNode(nodeId, def, upstream, workflowId, executionId, traceId, results, anyDegraded, aborted);
                        }
                    } finally {
                        slots.release();
                        // 失败也算"处理过"：不标记会让下一层永远等不到，而循环已由 aborted 收口
                        done.add(nodeId);
                    }
                }, nodePool));
            }
            CompletableFuture.allOf(inFlight.toArray(new CompletableFuture[0])).join();
        }

        if (aborted.get() || done.size() != order.size()) {
            store.finish(executionId, ExecutionStatus.FAILED.name());
            return new ExecutionResult(executionId, ExecutionStatus.FAILED, ordered(results, order));
        }

        ExecutionStatus finalStatus = anyDegraded.get() ? ExecutionStatus.SUCCESS_WITH_DEGRADED : ExecutionStatus.SUCCESS;
        store.finish(executionId, finalStatus.name());
        return new ExecutionResult(executionId, finalStatus, ordered(results, order));
    }

    /** 单节点：SPI 四件套（超时、异常分类、指数退避重试、降级显性）全部在这里，语义与串行版本逐字一致。 */
    private void runNode(String nodeId, WorkflowDefinition def, Map<String, Object> upstream, long workflowId,
            long executionId, String traceId, List<NodeRunResult> results,
            AtomicBoolean anyDegraded, AtomicBoolean aborted) {
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
            // 节点可以在不抛异常的情况下自报降级（典型：多源聚合只拿到部分源）。
            // 成功路径也必须认这个标志，否则"禁止静默降级"的合同在这里直接失效——
            // 改之前引擎只看 lastError，degraded=true 的返回值会被记成 SUCCESS。
            boolean selfDegraded = output.degraded();
            if (selfDegraded) {
                anyDegraded.set(true);
                log.warn("[traceId={}] 节点 {} 返回降级输出（未抛异常）: {}", traceId, nodeId, output.degradeReason());
            }
            record(executionId, results, new NodeRunResult(nodeId, nodeDef.type(),
                    selfDegraded ? NodeStatus.DEGRADED : NodeStatus.SUCCESS,
                    usedAttempts, durationMs, output.data(), selfDegraded ? output.degradeReason() : null));
        } else {
            Optional<NodeOutput> degraded = tryDegrade(executor, input, lastError);
            if (degraded.isPresent()) {
                anyDegraded.set(true);
                NodeOutput d = degraded.get();
                upstream.put(nodeId, d.data());
                record(executionId, results, new NodeRunResult(nodeId, nodeDef.type(), NodeStatus.DEGRADED,
                        usedAttempts, durationMs, d.data(), d.degradeReason()));
                log.warn("[traceId={}] 节点 {} 走降级路径继续执行: {}", traceId, nodeId, d.degradeReason());
            } else {
                record(executionId, results, new NodeRunResult(nodeId, nodeDef.type(), NodeStatus.FAILED,
                        usedAttempts, durationMs, Map.of(),
                        brief(lastError) + (outcomeUnknown ? SIDE_EFFECT_NOTE : "")));
                log.error("[traceId={}] 节点 {} 重试耗尽且无降级，执行终止: {}", traceId, nodeId, brief(lastError));
                aborted.set(true);
            }
        }
    }

    private void record(long executionId, List<NodeRunResult> results, NodeRunResult r) {
        // 并发完成顺序不定，落库与收集放在同一临界区：时间线一次插入不被交错写撕裂
        synchronized (results) {
            results.add(r);
            store.appendNodeLog(executionId, r);
        }
    }

    private static Map<String, Set<String>> predecessors(WorkflowDefinition def) {
        Map<String, Set<String>> preds = new HashMap<>();
        for (NodeDef n : def.nodes()) {
            preds.put(n.id(), new HashSet<>());
        }
        List<EdgeDef> edges = def.edges() == null ? List.of() : def.edges();
        for (EdgeDef e : edges) {
            Set<String> from = preds.get(e.to());
            if (from != null) {
                from.add(e.from());
            }
        }
        return preds;
    }

    /** 返回值按拓扑位序排列：执行时间线要稳定可读，不能随并发完成顺序漂移。 */
    private static List<NodeRunResult> ordered(List<NodeRunResult> results, List<String> order) {
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            rank.put(order.get(i), i);
        }
        List<NodeRunResult> out = new ArrayList<>(results);
        out.sort(Comparator.comparingInt(r -> rank.getOrDefault(r.nodeId(), 0)));
        return List.copyOf(out);
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

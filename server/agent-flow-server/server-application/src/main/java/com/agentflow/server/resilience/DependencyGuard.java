package com.agentflow.server.resilience;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.server.config.ResilienceProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 下游依赖的进程内舱壁 + 熔断（P7-3#17）。
 *
 * 为什么在节点入口做而不是在 HttpJson 里做：LLM 走 Spring AI SDK、微信/万相走裸 HttpClient、
 * 网页抓取走 HttpJson，出网点不统一；而所有外部调用都必须经过 NodeExecutor。按节点类型映射到
 * 依赖（llm/image/wechat/xhs/web），一处收口，新增同类节点自动继承策略。
 *
 * 语义：
 * - 舱壁：每个依赖独立信号量，慢依赖最多占 N 个执行线程，不能挤占全部执行槽；
 * - 熔断：连续 N 次"依赖侧失败"后 open，open 期间直接 C0004（引擎判不可重试，不打下游）；
 * - half-open：冷却后放 1 个探测，成功 closed，失败重新 open；
 * - 客户端类错误（A0400 等）不算依赖失败，反而证明对端可达，会重置失败计数。
 */
@Component
public class DependencyGuard {

    private static final Logger log = LoggerFactory.getLogger(DependencyGuard.class);

    /** 节点类型 → 依赖名。未列出的节点（echo/publish.local 等纯本地节点）不做韧性包装。 */
    private static final Map<String, String> TYPE_DEPENDENCY = Map.of(
            "llm.chat", "llm",
            "copy.write", "llm",
            "copy.originality", "llm",
            "image.cover", "image",
            "image.wanxiang", "image",
            "wechat.draft", "wechat",
            "xhs.draft", "xhs",
            "hotspot.fetch", "web",
            "article.extract", "web",
            "http.collect", "web");

    private final ResilienceProperties props;
    private final MeterRegistry meter;
    private final Map<String, Guarded> guards = new ConcurrentHashMap<>();

    public DependencyGuard(ResilienceProperties props, MeterRegistry meter) {
        this.props = props;
        this.meter = meter;
        if (props.enabled()) {
            for (String dependency : props.dependencyNames()) {
                Gauge.builder("agentflow.dependency.state", () -> stateValue(dependency))
                        .description("0=closed 1=half-open 2=open")
                        .tag("dependency", dependency)
                        .register(meter);
            }
        }
    }

    public NodeExecutor wrap(NodeExecutor delegate) {
        String dependency = TYPE_DEPENDENCY.get(delegate.type());
        if (!props.enabled() || dependency == null) {
            return delegate;
        }
        return new ResilientExecutor(delegate, dependency, guards.computeIfAbsent(dependency, this::newGuarded));
    }

    /** 供测试与运维排查：返回 0=closed / 1=half-open / 2=open。 */
    public int stateValue(String dependency) {
        Guarded guarded = guards.get(dependency);
        return guarded == null ? 0 : guarded.stateValue();
    }

    private Guarded newGuarded(String dependency) {
        return new Guarded(dependency, props.policyFor(dependency), meter);
    }

    private final class ResilientExecutor implements NodeExecutor {

        private final NodeExecutor delegate;
        private final String dependency;
        private final Guarded guarded;

        private ResilientExecutor(NodeExecutor delegate, String dependency, Guarded guarded) {
            this.delegate = delegate;
            this.dependency = dependency;
            this.guarded = guarded;
        }

        @Override
        public String type() {
            return delegate.type();
        }

        @Override
        public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
            return guarded.call(dependency, () -> delegate.execute(input, ctx));
        }

        @Override
        public RetryPolicy retryPolicy() {
            return delegate.retryPolicy();
        }

        @Override
        public Duration timeout() {
            return delegate.timeout();
        }

        @Override
        public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
            return delegate.degrade(input, lastError);
        }

        @Override
        public boolean hasSideEffects() {
            return delegate.hasSideEffects();
        }
    }

    private static final class Guarded {

        private enum State { CLOSED, HALF_OPEN, OPEN }

        private final String dependency;
        private final int failureThreshold;
        private final long openNanos;
        private final Semaphore permits;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final MeterRegistry meter;

        private State state = State.CLOSED;
        private long openUntilNanos;
        private int probesInFlight;

        private Guarded(String dependency, ResilienceProperties.Policy policy, MeterRegistry meter) {
            this.dependency = dependency;
            this.failureThreshold = Math.max(1, policy.failureThreshold());
            this.openNanos = Math.max(1, policy.openDuration().toNanos());
            this.permits = new Semaphore(Math.max(1, policy.maxConcurrent()));
            this.meter = meter;
        }

        private <T> T call(String dependency, java.util.function.Supplier<T> action) {
            Admission admission = admit();
            if (admission == Admission.REJECT_OPEN) {
                reject("open");
                throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "依赖 " + dependency + " 熔断中，直接快速失败（不打下游）");
            }
            if (!permits.tryAcquire()) {
                if (admission == Admission.PROBE) {
                    releaseProbe();
                }
                reject("bulkhead");
                throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "依赖 " + dependency + " 并发舱壁已满（上限 " + permits.availablePermits() + "+1）");
            }
            boolean probe = admission == Admission.PROBE;
            try {
                T result = action.get();
                onHealthy(probe);
                return result;
            } catch (ApiException e) {
                onFailed(isDependencyFailure(e), probe);
                throw e;
            } catch (RuntimeException e) {
                onFailed(true, probe);
                throw e;
            } finally {
                permits.release();
            }
        }

        private synchronized Admission admit() {
            if (state == State.OPEN) {
                if (System.nanoTime() < openUntilNanos) {
                    return Admission.REJECT_OPEN;
                }
                state = State.HALF_OPEN;
                probesInFlight = 0;
            }
            if (state == State.HALF_OPEN) {
                if (probesInFlight >= 1) {
                    return Admission.REJECT_OPEN;
                }
                probesInFlight++;
                return Admission.PROBE;
            }
            return Admission.PASS;
        }

        private synchronized void releaseProbe() {
            if (probesInFlight > 0) {
                probesInFlight--;
            }
        }

        private synchronized void onHealthy(boolean probe) {
            consecutiveFailures.set(0);
            if (state == State.HALF_OPEN) {
                state = State.CLOSED;
                probesInFlight = 0;
                log.info("依赖 {} 半开探测成功，熔断恢复", dependency);
            } else if (probe) {
                probesInFlight = 0;
            }
        }

        private synchronized void onFailed(boolean dependencyFailure, boolean probe) {
            if (!dependencyFailure) {
                // 对端回了 4xx/校验收敛：可达性没问题，按健康信号处理
                consecutiveFailures.set(0);
                if (state == State.HALF_OPEN) {
                    state = State.CLOSED;
                    probesInFlight = 0;
                    log.info("依赖 {} 半开探测收到客户端类错误，判定可达，熔断恢复", dependency);
                } else if (probe) {
                    probesInFlight = 0;
                }
                return;
            }
            if (state == State.HALF_OPEN) {
                open();
                return;
            }
            if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
                open();
            } else if (probe) {
                probesInFlight = 0;
            }
        }

        private void open() {
            state = State.OPEN;
            openUntilNanos = System.nanoTime() + openNanos;
            probesInFlight = 0;
            log.warn("依赖 {} 连续失败达到阈值 {}，熔断 {}s", dependency, failureThreshold,
                    Duration.ofNanos(openNanos).toSeconds());
            if (meter != null) {
                meter.counter("agentflow.dependency.trips", "dependency", dependency).increment();
            }
        }

        private void reject(String reason) {
            if (meter != null) {
                meter.counter("agentflow.dependency.rejected", "dependency", dependency, "reason", reason).increment();
            }
        }

        private synchronized int stateValue() {
            if (state == State.OPEN && System.nanoTime() >= openUntilNanos) {
                return 1; // 冷却结束，下一次调用会进入 half-open
            }
            return switch (state) {
                case CLOSED -> 0;
                case HALF_OPEN -> 1;
                case OPEN -> 2;
            };
        }

        private static boolean isDependencyFailure(ApiException e) {
            return switch (e.errorCode()) {
                case UPSTREAM_REJECTED, UPSTREAM_TIMEOUT, UPSTREAM_PROTOCOL_ERROR -> true;
                default -> false;
            };
        }

        private enum Admission { PASS, PROBE, REJECT_OPEN }
    }
}

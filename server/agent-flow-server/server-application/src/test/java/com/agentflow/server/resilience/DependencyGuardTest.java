package com.agentflow.server.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.server.config.ResilienceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** P7-3#17：舱壁、熔断开合、半开单探测与客户端错误口径。 */
class DependencyGuardTest {

    private static final NodeInput INPUT = new NodeInput(Map.of(), Map.of());
    private static final ExecutionContext CTX = new ExecutionContext("trace", 1L, 1L);

    private final SimpleMeterRegistry meter = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
    }

    private ResilienceProperties props(boolean enabled, int maxConcurrent, int threshold, Duration openDuration) {
        return new ResilienceProperties(enabled,
                Map.of("web", new ResilienceProperties.Policy(maxConcurrent, threshold, openDuration)));
    }

    private NodeExecutor node(String type, java.util.function.Supplier<NodeOutput> body) {
        return new NodeExecutor() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
                return body.get();
            }
        };
    }

    private DependencyGuard guard(ResilienceProperties props) {
        return new DependencyGuard(props, meter);
    }

    @Test
    void bulkheadRejectsWhenPermitExhaustedAndAcquiresAgainAfterRelease() throws Exception {
        DependencyGuard guard = guard(props(true, 1, 10, Duration.ofSeconds(30)));
        CountDownLatch entered = new CountDownLatch(1);
        NodeExecutor slow = node("http.collect", () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NodeOutput.ok(Map.of());
        });
        NodeExecutor wrapped = guard.wrap(slow);

        Thread first = new Thread(() -> wrapped.execute(INPUT, CTX));
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> wrapped.execute(INPUT, CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .hasMessageContaining("并发舱壁已满");
        assertThat(meter.counter("agentflow.dependency.rejected", "dependency", "web", "reason", "bulkhead")
                .count()).isEqualTo(1.0);

        release.countDown();
        first.join(5000);
        assertThat(wrapped.execute(INPUT, CTX)).isNotNull(); // 释放后可重新进入
    }

    @Test
    void opensAfterConsecutiveDependencyFailuresAndStopsCallingDownstream() {
        DependencyGuard guard = guard(props(true, 8, 2, Duration.ofSeconds(30)));
        AtomicInteger calls = new AtomicInteger();
        NodeExecutor failing = node("http.collect", () -> {
            calls.incrementAndGet();
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "stub 超时");
        });
        NodeExecutor wrapped = guard.wrap(failing);

        assertThatThrownBy(() -> wrapped.execute(INPUT, CTX)).isInstanceOf(ApiException.class);
        assertThat(guard.stateValue("web")).isZero();
        assertThatThrownBy(() -> wrapped.execute(INPUT, CTX)).isInstanceOf(ApiException.class);
        assertThat(guard.stateValue("web")).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);

        assertThatThrownBy(() -> wrapped.execute(INPUT, CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .hasMessageContaining("熔断中");
        assertThat(calls.get()).as("open 期间不得打下游").isEqualTo(2);
        assertThat(meter.counter("agentflow.dependency.trips", "dependency", "web").count()).isEqualTo(1.0);
    }

    @Test
    void clientClassErrorsProveReachabilityAndNeverTrip() {
        DependencyGuard guard = guard(props(true, 8, 1, Duration.ofSeconds(30)));
        NodeExecutor badRequest = node("http.collect",
                () -> {
                    throw new ApiException(ErrorCode.BAD_REQUEST, "参数不对");
                });
        NodeExecutor wrapped = guard.wrap(badRequest);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> wrapped.execute(INPUT, CTX)).isInstanceOf(ApiException.class);
        }
        assertThat(guard.stateValue("web")).as("A04xx 不算依赖失败").isZero();
    }

    @Test
    void halfOpenProbeSuccessClosesCircuit() throws Exception {
        DependencyGuard guard = guard(props(true, 8, 1, Duration.ofMillis(20)));
        AtomicInteger calls = new AtomicInteger();
        NodeExecutor flaky = node("http.collect", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ApiException(ErrorCode.UPSTREAM_REJECTED, "stub 拒连");
            }
            return NodeOutput.ok(Map.of("ok", true));
        });
        NodeExecutor wrapped = guard.wrap(flaky);

        assertThatThrownBy(() -> wrapped.execute(INPUT, CTX)).isInstanceOf(ApiException.class);
        assertThat(guard.stateValue("web")).isEqualTo(2);

        Thread.sleep(30);
        assertThat(guard.stateValue("web")).as("冷却结束呈现半开").isEqualTo(1);
        assertThat(wrapped.execute(INPUT, CTX)).isNotNull();
        assertThat(guard.stateValue("web")).isZero();
    }

    @Test
    void halfOpenProbeFailureReopensCircuit() throws Exception {
        DependencyGuard guard = guard(props(true, 8, 1, Duration.ofMillis(20)));
        AtomicInteger calls = new AtomicInteger();
        NodeExecutor failing = node("http.collect", () -> {
            calls.incrementAndGet();
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "stub 500");
        });
        NodeExecutor wrapped = guard.wrap(failing);

        assertThatThrownBy(() -> wrapped.execute(INPUT, CTX)).isInstanceOf(ApiException.class);
        assertThat(guard.stateValue("web")).isEqualTo(2);
        Thread.sleep(30);
        assertThatThrownBy(() -> wrapped.execute(INPUT, CTX)).isInstanceOf(ApiException.class);
        assertThat(guard.stateValue("web")).as("探测失败立即重新 open").isEqualTo(2);

        assertThatThrownBy(() -> wrapped.execute(INPUT, CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void disabledGuardReturnsDelegateUnchanged() {
        DependencyGuard guard = guard(props(false, 4, 1, Duration.ofSeconds(30)));
        NodeExecutor delegate = node("http.collect", () -> NodeOutput.ok(Map.of()));
        assertThat(guard.wrap(delegate)).isSameAs(delegate);
    }

    @Test
    void localNodesAreNotWrapped() {
        DependencyGuard guard = guard(props(true, 4, 1, Duration.ofSeconds(30)));
        NodeExecutor echo = node("echo", () -> NodeOutput.ok(Map.of()));
        assertThat(guard.wrap(echo)).isSameAs(echo);
    }

    @Test
    void wrapperDelegatesMetadataSoEngineSemanticsAreUnchanged() {
        DependencyGuard guard = guard(props(true, 4, 1, Duration.ofSeconds(30)));
        NodeExecutor delegate = new NodeExecutor() {
            @Override
            public String type() {
                return "wechat.draft";
            }

            @Override
            public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
                return NodeOutput.ok(Map.of());
            }

            @Override
            public RetryPolicy retryPolicy() {
                return RetryPolicy.noRetry();
            }

            @Override
            public Duration timeout() {
                return Duration.ofSeconds(7);
            }

            @Override
            public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
                return Optional.of(NodeOutput.degraded(Map.of(), "stub"));
            }

            @Override
            public boolean hasSideEffects() {
                return true;
            }
        };
        NodeExecutor wrapped = guard.wrap(delegate);
        assertThat(wrapped.type()).isEqualTo("wechat.draft");
        assertThat(wrapped.retryPolicy()).isEqualTo(delegate.retryPolicy());
        assertThat(wrapped.timeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(wrapped.hasSideEffects()).isTrue();
        assertThat(wrapped.degrade(INPUT, new RuntimeException())).isPresent();
    }
}

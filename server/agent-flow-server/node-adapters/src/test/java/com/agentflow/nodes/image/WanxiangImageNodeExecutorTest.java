package com.agentflow.nodes.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.StubHttp;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** image.wanxiang：异步提交+轮询协议、三异常、noRetry 防重复计费、无图降级。 */
class WanxiangImageNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 1L, 1L);

    private static NodeInput input(Map<String, Object> extra) {
        Map<String, Object> params = new java.util.HashMap<>(extra);
        params.putIfAbsent("prompt", "科技感封面图");
        return new NodeInput(params, Map.of());
    }

    @Test
    void submitsAsyncTaskAndPollsUntilSucceeded() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicBoolean asyncHeaderSeen = new AtomicBoolean();
        try (StubHttp stub = new StubHttp(ex -> {
            if (ex.getRequestURI().getPath().contains("image-synthesis")) {
                asyncHeaderSeen.set("enable".equals(ex.getRequestHeaders().getFirst("X-DashScope-Async")));
                StubHttp.respond(ex, 200, "{\"output\":{\"task_id\":\"t-9527\"}}");
            } else {
                if (polls.incrementAndGet() == 1) {
                    StubHttp.respond(ex, 200, "{\"output\":{\"task_status\":\"RUNNING\"}}");
                } else {
                    StubHttp.respond(ex, 200,
                            "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"url\":\"https://img.example/1.png\"}]}}");
                }
            }
        })) {
            WanxiangImageNodeExecutor executor = new WanxiangImageNodeExecutor("sk-test", stub.baseUrl(), Duration.ofSeconds(5));
            NodeOutput out = executor.execute(input(Map.of("pollIntervalMs", 30, "maxWaitMs", 5000)), CTX);

            assertThat(asyncHeaderSeen).isTrue(); // 缺异步头 DashScope 会走同步长连接，协议必须钉死
            assertThat(out.data()).containsEntry("imageUrl", "https://img.example/1.png")
                    .containsEntry("taskId", "t-9527");
            assertThat(out.data()).containsKey("costMs");
            assertThat(polls.get()).isEqualTo(2);
            assertThat(stub.requestLog()).startsWith("POST /api/v1/services/aigc/text2image/image-synthesis");
        }
    }

    @Test
    void failedTaskMapsToProtocolError() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> {
            if (ex.getRequestURI().getPath().contains("image-synthesis")) {
                StubHttp.respond(ex, 200, "{\"output\":{\"task_id\":\"t-1\"}}");
            } else {
                StubHttp.respond(ex, 200,
                        "{\"output\":{\"task_status\":\"FAILED\",\"code\":\"IPInfringementSuspect\",\"message\":\"内容涉及侵权\"}}");
            }
        })) {
            WanxiangImageNodeExecutor executor = new WanxiangImageNodeExecutor("sk-test", stub.baseUrl(), Duration.ofSeconds(5));
            assertThatThrownBy(() -> executor.execute(input(Map.of("pollIntervalMs", 20, "maxWaitMs", 3000)), CTX))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR);
                        assertThat(e.getMessage()).contains("IPInfringementSuspect");
                    });
        }
    }

    @Test
    void submitHttpErrorMapsToProtocolError() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> StubHttp.respond(ex, 400, "{\"code\":\"InvalidParameter\"}"))) {
            WanxiangImageNodeExecutor executor = new WanxiangImageNodeExecutor("sk-test", stub.baseUrl(), Duration.ofSeconds(5));
            assertThatThrownBy(() -> executor.execute(input(Map.of()), CTX))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
        }
    }

    @Test
    void waitWindowExceededMapsToTimeout() throws Exception {
        try (StubHttp stub = new StubHttp(ex -> {
            if (ex.getRequestURI().getPath().contains("image-synthesis")) {
                StubHttp.respond(ex, 200, "{\"output\":{\"task_id\":\"t-slow\"}}");
            } else {
                StubHttp.respond(ex, 200, "{\"output\":{\"task_status\":\"PENDING\"}}");
            }
        })) {
            WanxiangImageNodeExecutor executor = new WanxiangImageNodeExecutor("sk-test", stub.baseUrl(), Duration.ofSeconds(5));
            assertThatThrownBy(() -> executor.execute(
                    input(Map.of("pollIntervalMs", 30, "maxWaitMs", 150)), CTX))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));
        }
    }

    @Test
    void connectionRefusedMapsToRejected() throws Exception {
        WanxiangImageNodeExecutor executor =
                new WanxiangImageNodeExecutor("sk-test", StubHttp.closedPortUrl(), Duration.ofSeconds(5));
        assertThatThrownBy(() -> executor.execute(input(Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_REJECTED));
    }

    @Test
    void missingApiKeyIsNonRetryableBadRequest() {
        WanxiangImageNodeExecutor executor = new WanxiangImageNodeExecutor("", "https://x.example", Duration.ofSeconds(5));
        assertThatThrownBy(() -> executor.execute(input(Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("BAILIAN_API_KEY");
                });
    }

    @Test
    void missingPromptIsBadRequest() {
        WanxiangImageNodeExecutor executor = new WanxiangImageNodeExecutor("sk-test", "https://x.example", Duration.ofSeconds(5));
        assertThatThrownBy(() -> executor.execute(new NodeInput(Map.of(), Map.of()), CTX))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void retryMustBeZeroToPreventDoubleBilling() {
        // 钱敏感契约：引擎重试=二次提交生图任务=重复计费。任何人改成 >0 先过这条断言。
        assertThat(new WanxiangImageNodeExecutor("sk").retryPolicy()).isEqualTo(RetryPolicy.noRetry());
        assertThat(new WanxiangImageNodeExecutor("sk").timeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void degradeOutputsNoImageVisibly() {
        NodeOutput degraded = new WanxiangImageNodeExecutor("sk")
                .degrade(input(Map.of()), new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "等待超限"))
                .orElseThrow();
        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.data()).containsEntry("imageUrl", "");
        assertThat(degraded.degradeReason()).contains("无图");
    }
}

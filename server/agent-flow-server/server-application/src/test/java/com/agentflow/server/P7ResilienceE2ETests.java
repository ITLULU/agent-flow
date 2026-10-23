package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.registry.NodeRegistry;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.server.resilience.DependencyGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P7-3#17 端到端：真 Spring 装配 + 真 HTTP 桩，验证 500 连续失败触发熔断后
 * 后续调用直接 C0004 且桩不再收到请求；冷却后半开探测成功自动恢复。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-resilience;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=false",
                "agentflow.nodes.llm.api-key=",
                "agentflow.nodes.image.api-key=",
                "agentflow.resilience.dependencies.web.max-concurrent=8",
                "agentflow.resilience.dependencies.web.failure-threshold=2",
                "agentflow.resilience.dependencies.web.open-duration=400ms"})
class P7ResilienceE2ETests {

    @Autowired
    NodeRegistry registry;

    @Autowired
    DependencyGuard guard;

    private HttpServer stub;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicBoolean healthy = new AtomicBoolean(false);
    private String url;

    @BeforeEach
    void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/collect", exchange -> {
            hits.incrementAndGet();
            boolean ok = healthy.get();
            byte[] body = (ok ? "<html><head><title>恢复</title></head><body>ok</body></html>"
                    : "upstream exploded").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        url = "http://127.0.0.1:" + stub.getAddress().getPort() + "/collect";
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    @Test
    void repeated500OpensCircuitStopsTrafficThenHalfOpenProbeRecovers() throws Exception {
        NodeExecutor collect = registry.require("http.collect");
        NodeInput input = new NodeInput(Map.of("url", url), Map.of());
        ExecutionContext ctx = new ExecutionContext("resilience-e2e", 1L, 1L);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> collect.execute(input, ctx))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
        }
        assertThat(hits.get()).isEqualTo(2);
        assertThat(guard.stateValue("web")).isEqualTo(2);

        assertThatThrownBy(() -> collect.execute(input, ctx))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertThat(hits.get()).as("open 期间不得再打真实下游").isEqualTo(2);

        healthy.set(true);
        assertThat(guard.stateValue("web")).as("冷却期内仍为 open").isEqualTo(2);
        Thread.sleep(450);
        assertThat(guard.stateValue("web")).as("冷却结束呈现半开").isEqualTo(1);

        NodeOutput recovered = collect.execute(input, ctx);
        assertThat(recovered.degraded()).isFalse();
        assertThat(recovered.data()).containsEntry("title", "恢复");
        assertThat(hits.get()).isEqualTo(3);
        assertThat(guard.stateValue("web")).isZero();
    }
}

package com.agentflow.nodes.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.engine.WorkflowEngine;
import com.agentflow.engine.model.ExecutionResult;
import com.agentflow.engine.model.ExecutionStatus;
import com.agentflow.engine.model.NodeDef;
import com.agentflow.engine.model.NodeRunResult;
import com.agentflow.engine.model.TriggerDef;
import com.agentflow.engine.model.WorkflowDefinition;
import com.agentflow.engine.port.ExecutionLogStore;
import com.agentflow.engine.registry.NodeRegistry;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.StubHttp;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 评审 P6 #7 的真实 HTTP 面复现：draft/add 卡住 ⇒ 引擎判超时。
 * 修之前引擎会按 RetryPolicy(2,…) 再发两次，微信侧因此可能出现 3 篇同名草稿；
 * 现在必须"只发一次 + 中断在跑的请求 + 明确告诉执行人结果不可知"。
 */
class WechatDraftTimeoutTest {

    /** 只关心 draft/add 被打到几次；token 立即返回，让卡点落在真正发布那一步。 */
    private final AtomicInteger draftCalls = new AtomicInteger();
    private StubHttp server;

    @BeforeEach
    void startStub() throws IOException {
        server = new StubHttp(this::route);
    }

    private void route(HttpExchange ex) throws IOException {
        switch (ex.getRequestURI().getPath()) {
            case "/cgi-bin/token" -> StubHttp.respond(ex, 200, "{\"access_token\":\"tok-1\",\"expires_in\":7200}");
            case "/cgi-bin/draft/add" -> {
                draftCalls.incrementAndGet(); // 请求已到对端：效果是否落地从此不可知
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                StubHttp.respond(ex, 200, "{\"errcode\":0,\"media_id\":\"draft-late\"}");
            }
            default -> StubHttp.respond(ex, 404, "{}");
        }
    }

    @AfterEach
    void stopStub() {
        server.close();
    }

    /** 引擎超时压到 300ms（真实 60s 等不起测试），退避压到 5ms（真实 1500ms 与结论无关）。 */
    private WechatDraftNodeExecutor slowDraftNode() {
        return new WechatDraftNodeExecutor("wx-appid", "wx-secret", server.baseUrl(), Duration.ofSeconds(5)) {
            @Override
            public Duration timeout() {
                return Duration.ofMillis(300);
            }

            @Override
            public RetryPolicy retryPolicy() {
                return new RetryPolicy(2, 5L);
            }
        };
    }

    @Test
    void engineTimeoutPublishesExactlyOnceAndGivesUp() throws Exception {
        WechatDraftNodeExecutor node = slowDraftNode();
        NodeRegistry registry = new NodeRegistry();
        registry.register(node);
        WorkflowEngine engine = new WorkflowEngine(registry, new NoopStore());
        WorkflowDefinition def = new WorkflowDefinition("wechat-timeout", TriggerDef.manual(),
                List.of(new NodeDef("d", WechatDraftNodeExecutor.TYPE,
                        Map.of("title", "只该发一次的草稿", "content", "<p>正文</p>"))), List.of());

        ExecutionResult r = engine.run(11L, 1L, def, "trace-wx-timeout");
        NodeRunResult failed = r.nodeResults().get(0);

        assertThat(r.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(failed.attempts()).as("结果不可知后不再补刀重试").isEqualTo(1);
        assertThat(draftCalls.get()).as("发布请求只允许到达对端一次").isEqualTo(1);
        assertThat(failed.reason()).contains("Timeout");
        assertThat(failed.reason()).contains(WorkflowEngine.SIDE_EFFECT_NOTE);

        Thread.sleep(900); // 僵尸若没被中断，这会儿会把第二次尝试也发出去
        assertThat(draftCalls.get()).isEqualTo(1);
    }

    private static final class NoopStore implements ExecutionLogStore {
        @Override
        public long createExecution(long workflowId, String traceId) {
            return 11L;
        }

        @Override
        public void appendNodeLog(long executionId, NodeRunResult result) {
        }

        @Override
        public void finish(long executionId, String status) {
        }
    }
}

package com.agentflow.nodes.core;

import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * core.echo —— 无外部依赖的基准节点：引擎冒烟（P1 DoD）与契约回归用。
 * 它没有外部调用，因此显式声明不重试、不降级，作为"最简合规实现"的样板。
 */
public class EchoNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "core.echo";
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        Map<String, Object> data = new HashMap<>();
        data.put("echo", input.paramAsString("message", ""));
        data.put("traceId", ctx.traceId());
        return NodeOutput.ok(Map.copyOf(data));
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.noRetry();
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(5);
    }

    @Override
    public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
        return Optional.empty();
    }
}

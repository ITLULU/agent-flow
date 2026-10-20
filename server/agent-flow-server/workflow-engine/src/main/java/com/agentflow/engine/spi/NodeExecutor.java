package com.agentflow.engine.spi;

import java.time.Duration;
import java.util.Optional;

/**
 * 节点执行器 SPI —— 所有节点实现的强制契约（外部调用四件套：超时/捕获/降级/重试）。
 * 实现类必须：
 * 1. execute 内部完成异常捕获并转为 ApiException(UPSTREAM_*)，禁止把原始堆栈抛给引擎外层；
 * 2. 覆盖 degrade 并真实处理 连接拒绝/超时/协议异常 三类，降级输出必须 degraded=true；
 * 3. 按自身外部依赖特性覆盖 timeout / retryPolicy，不允许依赖"看着办"的默认值跑生产。
 */
public interface NodeExecutor {

    /** 全局唯一类型标识（"llm.chat"、"http.collect" 等），注册进 NodeRegistry。 */
    String type();

    NodeOutput execute(NodeInput input, ExecutionContext ctx);

    default RetryPolicy retryPolicy() {
        return RetryPolicy.defaultPolicy();
    }

    /** 单节点执行超时上限。注意：这是引擎层唯一超时口径，节点内部不要再叠一层各自为政的超时。 */
    default Duration timeout() {
        return Duration.ofSeconds(60);
    }

    /**
     * 重试耗尽后的降级路径。默认不降级（直接失败），有降级诉求的节点必须覆盖并配三异常单测钉住。
     */
    default Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
        return Optional.empty();
    }
}

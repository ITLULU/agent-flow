package com.agentflow.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行闸门配置（agentflow.exec.*）。
 * 容量必须在运行期可调：编译期常量意味着每次扩缩容都要重新构建镜像，应急时等于容量不可调（评审 P7-3 #10）。
 */
@ConfigurationProperties(prefix = "agentflow.exec")
public record ExecProperties(Integer maxConcurrent, Duration acquireTimeout, Integer maxQueued) {

    public ExecProperties {
        maxConcurrent = maxConcurrent == null ? 16 : maxConcurrent;
        acquireTimeout = acquireTimeout == null ? Duration.ofMinutes(5) : acquireTimeout;
        maxQueued = maxQueued == null ? 64 : maxQueued;
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("agentflow.exec.max-concurrent 必须 >= 1，当前: " + maxConcurrent);
        }
        if (acquireTimeout.isZero() || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("agentflow.exec.acquire-timeout 必须 > 0，当前: " + acquireTimeout);
        }
        if (maxQueued < 0) {
            throw new IllegalArgumentException("agentflow.exec.max-queued 必须 >= 0，当前: " + maxQueued);
        }
    }
}

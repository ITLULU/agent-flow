package com.agentflow.engine.spi;

/** 重试策略：默认 2 次重试 + 指数退避（首次 500ms）。 */
public record RetryPolicy(int maxAttempts, long firstBackoffMs) {

    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(2, 500L);
    }

    public static RetryPolicy noRetry() {
        return new RetryPolicy(0, 0L);
    }
}

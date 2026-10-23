package com.agentflow.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 引擎运行参数（并行支路宽度）。
 * 宽度上限保护的是下游舱壁与本进程连接/线程预算，不是越大越好；
 * 舱壁（agentflow.resilience.*）仍按依赖独立生效，这里只是同层节点的并发天花板。
 */
@ConfigurationProperties(prefix = "agentflow.engine")
public record EngineProperties(Integer maxParallelNodes) {

    /** yml 默认值必须与此一致，改一处就要改两处（有静态测试钉住）。 */
    public static final int DEFAULT_MAX_PARALLEL_NODES = 8;

    public EngineProperties {
        if (maxParallelNodes == null) {
            maxParallelNodes = DEFAULT_MAX_PARALLEL_NODES;
        }
        if (maxParallelNodes < 1) {
            throw new IllegalArgumentException("agentflow.engine.max-parallel-nodes 必须 >= 1");
        }
    }
}

package com.agentflow.server.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下游依赖韧性配置（P7-3#17）：按依赖分舱壁（并发上限）+ 连续失败熔断。
 * 未显式配置的依赖走 DEFAULTS；只覆盖单个字段时，其余字段仍取该依赖的默认值（不是通用默认）。
 */
@ConfigurationProperties(prefix = "agentflow.resilience")
public record ResilienceProperties(Boolean enabled, Map<String, Policy> dependencies) {

    public static final Map<String, Policy> DEFAULTS = Map.of(
            "llm", new Policy(8, 5, Duration.ofSeconds(30)),
            "image", new Policy(4, 5, Duration.ofSeconds(30)),
            "wechat", new Policy(4, 3, Duration.ofSeconds(60)),
            "xhs", new Policy(4, 3, Duration.ofSeconds(60)),
            "web", new Policy(8, 10, Duration.ofSeconds(30)));

    public ResilienceProperties {
        if (enabled == null) {
            enabled = true;
        }
        dependencies = dependencies == null ? Map.of() : Map.copyOf(dependencies);
    }

    public Policy policyFor(String dependency) {
        Policy base = DEFAULTS.getOrDefault(dependency, new Policy(4, 5, Duration.ofSeconds(30)));
        Policy override = dependencies.get(dependency);
        if (override == null) {
            return base;
        }
        return new Policy(
                override.maxConcurrent() == null ? base.maxConcurrent() : override.maxConcurrent(),
                override.failureThreshold() == null ? base.failureThreshold() : override.failureThreshold(),
                override.openDuration() == null ? base.openDuration() : override.openDuration());
    }

    /** 所有会注册指标的依赖名：内置默认 + 配置里新增的（允许把自定义节点归到新依赖）。 */
    public java.util.Set<String> dependencyNames() {
        java.util.Set<String> names = new java.util.TreeSet<>(DEFAULTS.keySet());
        names.addAll(dependencies.keySet());
        return names;
    }

    /** 三个字段允许为空：空=沿用该依赖的默认值。 */
    public record Policy(Integer maxConcurrent, Integer failureThreshold, Duration openDuration) {
    }
}

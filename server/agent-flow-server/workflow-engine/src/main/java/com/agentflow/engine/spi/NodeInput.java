package com.agentflow.engine.spi;

import java.util.Map;

/** 节点输入：params=工作流定义中的静态参数；upstream=上游节点输出（key 为节点 id）。 */
public record NodeInput(Map<String, Object> params, Map<String, Object> upstream) {

    public Object param(String key) {
        return params.get(key);
    }

    public String paramAsString(String key, String defaultValue) {
        Object v = params.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }
}

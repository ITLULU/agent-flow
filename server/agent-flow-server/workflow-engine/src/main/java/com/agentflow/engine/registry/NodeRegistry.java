package com.agentflow.engine.registry;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.NodeExecutor;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 节点类型注册表：type → executor。重复注册直接拒绝，防止静默覆盖。 */
public class NodeRegistry {

    private final Map<String, NodeExecutor> executors = new ConcurrentHashMap<>();

    public void register(NodeExecutor executor) {
        NodeExecutor prev = executors.putIfAbsent(executor.type(), executor);
        if (prev != null) {
            throw new ApiException(ErrorCode.WORKFLOW_INVALID, "重复节点类型: " + executor.type());
        }
    }

    public NodeExecutor require(String type) {
        NodeExecutor executor = executors.get(type);
        if (executor == null) {
            throw new ApiException(ErrorCode.WORKFLOW_INVALID, "节点类型未注册: " + type);
        }
        return executor;
    }

    public Set<String> knownTypes() {
        return Set.copyOf(executors.keySet());
    }
}

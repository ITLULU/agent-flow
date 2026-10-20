package com.agentflow.engine.model;

/** 单节点执行终态。DEGRADED = 走完降级路径，必须显性可见，禁止混入 SUCCESS。 */
public enum NodeStatus {
    SUCCESS, DEGRADED, FAILED
}

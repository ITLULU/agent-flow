package com.agentflow.engine.model;

/** 一次工作流执行的整体终态。 */
public enum ExecutionStatus {
    RUNNING, SUCCESS, SUCCESS_WITH_DEGRADED, FAILED
}

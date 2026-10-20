package com.agentflow.engine.port;

import com.agentflow.engine.model.NodeRunResult;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 执行日志端口（引擎→持久层的唯一出口，六边形架构：引擎不引 JDBC）。
 * 实现方：server-application 的 JdbcExecutionLogStore；测试可用 NOOP。
 */
public interface ExecutionLogStore {

    /** 创建执行记录（初始状态 RUNNING），返回 executionId。 */
    long createExecution(long workflowId, String traceId);

    /** 追加一条节点执行结果（含降级/失败原因）。 */
    void appendNodeLog(long executionId, NodeRunResult result);

    /** 收尾执行状态。 */
    void finish(long executionId, String status);

    /** 纯引擎测试用的内存实现（不落库，仅保证 id 单调可查）。 */
    ExecutionLogStore NOOP = new ExecutionLogStore() {
        private final AtomicLong seq = new AtomicLong();

        @Override
        public long createExecution(long workflowId, String traceId) {
            return seq.incrementAndGet();
        }

        @Override
        public void appendNodeLog(long executionId, NodeRunResult result) {
            // no-op
        }

        @Override
        public void finish(long executionId, String status) {
            // no-op
        }
    };
}

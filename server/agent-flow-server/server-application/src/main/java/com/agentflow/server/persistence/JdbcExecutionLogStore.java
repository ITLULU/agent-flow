package com.agentflow.server.persistence;

import com.agentflow.engine.model.NodeRunResult;
import com.agentflow.engine.port.ExecutionLogStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ExecutionLogStore 的 JDBC 实现（引擎不感知 SQL；节点输出 JSON 序列化落库并截断防爆行）。 */
@Repository
public class JdbcExecutionLogStore implements ExecutionLogStore {

    /** 单节点输出落库上限（字符），超出截断——大输出属于产物存储职责，不属于执行日志。 */
    private static final int MAX_OUTPUT_CHARS = 64_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcExecutionLogStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public long createExecution(long workflowId, String traceId) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        String actor = AuditActor.current();
        jdbc.update(connection -> {
            // 显式指定回列，避免 H2 把带 DEFAULT 的列当生成键多路返回
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO execution (workflow_id, trace_id, status, creator, updater) VALUES (?, ?, 'RUNNING', ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, workflowId);
            ps.setString(2, traceId);
            ps.setString(3, actor);
            ps.setString(4, actor);
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        if (id == null) {
            throw new IllegalStateException("execution 主键生成失败");
        }
        return id;
    }

    @Override
    public void appendNodeLog(long executionId, NodeRunResult result) {
        String actor = AuditActor.current();
        jdbc.update("INSERT INTO execution_node_log "
                        + "(execution_id, node_id, node_type, status, attempts, duration_ms, output_json, reason, "
                        + "creator, updater) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                executionId, result.nodeId(), result.nodeType(), result.status().name(), result.attempts(),
                result.durationMs(), toJson(result.output()), truncate(result.reason()), actor, actor);
    }

    @Override
    public void finish(long executionId, String status) {
        jdbc.update("UPDATE execution SET status = ?, finished_at = CURRENT_TIMESTAMP, "
                        + "update_time = CURRENT_TIMESTAMP, updater = ? WHERE id = ?",
                status, AuditActor.current(), executionId);
    }

    private String toJson(Object value) {
        try {
            return truncate(mapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            return "{\"serializationError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_OUTPUT_CHARS ? s : s.substring(0, MAX_OUTPUT_CHARS) + "...[truncated]";
    }
}

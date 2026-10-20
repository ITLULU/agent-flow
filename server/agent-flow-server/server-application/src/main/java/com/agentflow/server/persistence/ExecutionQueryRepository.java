package com.agentflow.server.persistence;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 执行详情读取（API 查询专用，与写入端口 ExecutionLogStore 分离，读写口径各自演进）。 */
@Repository
public class ExecutionQueryRepository {

    public record NodeLogView(String nodeId, String nodeType, String status, int attempts, long durationMs,
                              Object output, String reason) {
    }

    public record ExecutionView(long id, long workflowId, String traceId, String status,
                                String startedAt, String finishedAt, List<NodeLogView> nodes) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ExecutionQueryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public ExecutionView findById(long executionId) {
        List<ExecutionView> rows = jdbc.query(
                "SELECT id, workflow_id, trace_id, status, CAST(started_at AS VARCHAR) AS started_at, "
                        + "CAST(finished_at AS VARCHAR) AS finished_at FROM execution WHERE id = ?",
                (rs, i) -> new ExecutionView(rs.getLong("id"), rs.getLong("workflow_id"),
                        rs.getString("trace_id"), rs.getString("status"),
                        rs.getString("started_at"), rs.getString("finished_at"),
                        loadNodes(rs.getLong("id"))),
                executionId);
        return rows.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "execution " + executionId));
    }

    private List<NodeLogView> loadNodes(long executionId) {
        return jdbc.query("SELECT node_id, node_type, status, attempts, duration_ms, output_json, reason "
                        + "FROM execution_node_log WHERE execution_id = ? ORDER BY id",
                (rs, i) -> new NodeLogView(rs.getString("node_id"), rs.getString("node_type"),
                        rs.getString("status"),
                        rs.getInt("attempts"), rs.getLong("duration_ms"),
                        parseJson(rs.getString("output_json")), rs.getString("reason")),
                executionId);
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // 存量脏数据不应炸掉整个查询：原样回传字符串
            return json;
        }
    }
}

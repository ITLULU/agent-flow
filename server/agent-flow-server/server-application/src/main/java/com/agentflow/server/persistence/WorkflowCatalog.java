package com.agentflow.server.persistence;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 工作流定义仓库：DSL 原文入库（唯一事实源），读写都经同一 ObjectMapper。P3 起携 owner_id 归属。 */
@Repository
public class WorkflowCatalog {

    public record StoredWorkflow(long id, String name, String dsl, long ownerId) {
    }

    public record WorkflowSummary(long id, String name, int version, String createdAt, int nodeCount) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkflowCatalog(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 鉴权用的轻量归属查询：不存在返回 empty 而非抛错——凭证解析不能因为一个 id 把执行链路炸成 500。 */
    public OptionalLong ownerOf(long id) {
        return jdbc.query("SELECT owner_id FROM workflow WHERE id = ?",
                (rs, i) -> rs.getLong(1), id).stream().mapToLong(Long::longValue).findAny();
    }

    public long save(WorkflowDefinition def, long ownerId) {
        String dsl;
        try {
            dsl = mapper.writeValueAsString(def);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "DSL 序列化失败: " + e.getClass().getSimpleName());
        }
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            // 显式指定回列：H2 默认会把带 DEFAULT 的非空列（created_at）一并当生成键返回，getKey() 多键即炸
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO workflow (name, dsl, owner_id) VALUES (?, ?, ?)", new String[]{"id"});
            ps.setString(1, def.name() == null ? "unnamed" : def.name());
            ps.setString(2, dsl);
            ps.setLong(3, ownerId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public StoredWorkflow findById(long id) {
        return jdbc.query("SELECT id, name, dsl, owner_id FROM workflow WHERE id = ?",
                (rs, rowNum) -> new StoredWorkflow(rs.getLong("id"), rs.getString("name"),
                        rs.getString("dsl"), rs.getLong("owner_id")),
                id).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "workflow " + id));
    }

    /** ownerId=null 表示 admin 视角（全部）；列表只回摘要，DSL 原文留在库里。 */
    public List<WorkflowSummary> list(Long ownerId) {
        String sql = "SELECT id, name, version, CAST(created_at AS VARCHAR) AS created_at, dsl FROM workflow"
                + (ownerId == null ? "" : " WHERE owner_id = ?") + " ORDER BY id DESC";
        Object[] args = ownerId == null ? new Object[0] : new Object[]{ownerId};
        return jdbc.query(sql, (rs, rowNum) -> new WorkflowSummary(
                rs.getLong("id"), rs.getString("name"), rs.getInt("version"),
                rs.getString("created_at"), countNodes(rs.getString("dsl"))), args);
    }

    private int countNodes(String dsl) {
        try {
            return mapper.readTree(dsl).path("nodes").size();
        } catch (Exception e) {
            return 0; // 脏 DSL 不该让整个列表 500；打开单条时自有解析错误兜底
        }
    }

    /** 更新定义并递增版本号；调用方已做归属校验。 */
    public void update(long id, WorkflowDefinition def) {
        String dsl;
        try {
            dsl = mapper.writeValueAsString(def);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "DSL 序列化失败: " + e.getClass().getSimpleName());
        }
        int rows = jdbc.update("UPDATE workflow SET name = ?, dsl = ?, version = version + 1 WHERE id = ?",
                def.name() == null ? "unnamed" : def.name(), dsl, id);
        if (rows == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "workflow " + id);
        }
    }

    public WorkflowDefinition parseDefinition(long id) {
        StoredWorkflow row = findById(id);
        return parseDsl(row);
    }

    public WorkflowDefinition parseDsl(StoredWorkflow row) {
        try {
            return mapper.readValue(row.dsl(), WorkflowDefinition.class);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL, "workflow " + row.id() + " DSL 反序列化失败");
        }
    }
}

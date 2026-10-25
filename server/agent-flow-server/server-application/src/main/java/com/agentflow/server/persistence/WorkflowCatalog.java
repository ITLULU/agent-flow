package com.agentflow.server.persistence;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 工作流定义仓库：DSL 原文入库（唯一事实源），读写都经同一 ObjectMapper。P3 起携 owner_id 归属。 */
@Repository
public class WorkflowCatalog {

    public record StoredWorkflow(long id, String name, String dsl, long ownerId) {
    }

    public record WorkflowSummary(long id, String name, int version, String createdAt, String updateTime,
                                  String creator, String updater, int nodeCount) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkflowCatalog(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 鉴权用的轻量归属查询：不存在返回 empty 而非抛错——凭证解析不能因为一个 id 把执行链路炸成 500。 */
    public OptionalLong ownerOf(long id) {
        return jdbc.query("SELECT owner_id FROM workflow WHERE id = ? AND deleted = FALSE",
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
        String actor = AuditActor.current();
        jdbc.update(connection -> {
            // 显式指定回列：H2 默认会把带 DEFAULT 的非空列（create_time）一并当生成键返回，getKey() 多键即炸
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO workflow (name, dsl, owner_id, creator, updater) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, def.name() == null ? "unnamed" : def.name());
            ps.setString(2, dsl);
            ps.setLong(3, ownerId);
            ps.setString(4, actor);
            ps.setString(5, actor);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 读定义。逻辑删除的工作流在这里就是"不存在"（404）：
     * 打开画布、改 DSL、触发执行、看执行详情都走这条路，一处过滤即可全线隐身，不必每个调用点各判一次。
     */
    public StoredWorkflow findById(long id) {
        return jdbc.query("SELECT id, name, dsl, owner_id FROM workflow WHERE id = ? AND deleted = FALSE",
                (rs, rowNum) -> new StoredWorkflow(rs.getLong("id"), rs.getString("name"),
                        rs.getString("dsl"), rs.getLong("owner_id")),
                id).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "workflow " + id));
    }

    /** ownerId=null 表示 admin 视角（全部）；列表只回摘要，DSL 原文留在库里。beforeId 为游标（不含）。 */
    public List<WorkflowSummary> list(Long ownerId, int limit, Long beforeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, name, version, CAST(create_time AS VARCHAR) AS created_at, "
                        + "CAST(update_time AS VARCHAR) AS update_time, creator, updater, dsl FROM workflow");
        List<Object> args = new ArrayList<>();
        List<String> where = new ArrayList<>();
        // 逻辑删除的行永不进列表（不是"前端自己过滤"：admin 视角同样走这条 SQL）
        where.add("deleted = FALSE");
        if (ownerId != null) {
            where.add("owner_id = ?");
            args.add(ownerId);
        }
        if (beforeId != null) {
            where.add("id < ?");
            args.add(beforeId);
        }
        sql.append(" WHERE ").append(String.join(" AND ", where));
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, rowNum) -> new WorkflowSummary(
                rs.getLong("id"), rs.getString("name"), rs.getInt("version"),
                rs.getString("created_at"), rs.getString("update_time"),
                rs.getString("creator"), rs.getString("updater"), countNodes(rs.getString("dsl"))), args.toArray());
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
        int rows = jdbc.update("UPDATE workflow SET name = ?, dsl = ?, version = version + 1, "
                        + "update_time = CURRENT_TIMESTAMP, updater = ? WHERE id = ? AND deleted = FALSE",
                def.name() == null ? "unnamed" : def.name(), dsl, AuditActor.current(), id);
        if (rows == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "workflow " + id);
        }
    }

    /**
     * 逻辑删除：只翻 deleted 标记并记删除时刻，行、DSL、执行记录、成品目录全留。
     * 已是删除态的 id 再删一次报 404（`WHERE deleted = FALSE` 自然落 0 行）——
     * 不返回"成功"是为了让调用方能区分"我删掉了"和"这里本来就没有"。
     */
    public void softDelete(long id) {
        int rows = jdbc.update("UPDATE workflow SET deleted = TRUE, deleted_time = CURRENT_TIMESTAMP, "
                        + "schedule_enabled = FALSE, next_fire_at = NULL, "
                        + "update_time = CURRENT_TIMESTAMP, updater = ? WHERE id = ? AND deleted = FALSE",
                AuditActor.current(), id);
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

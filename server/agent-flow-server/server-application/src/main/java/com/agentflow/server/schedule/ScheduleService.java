package com.agentflow.server.schedule;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.trace.TraceIdUtils;
import com.agentflow.engine.model.WorkflowDefinition;
import com.agentflow.server.exec.ExecutionManager;
import com.agentflow.server.persistence.AuditActor;
import com.agentflow.server.persistence.WorkflowCatalog;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * cron 触发注册表：schedule_cron 用 Spring `CronExpression`（6 段，零新依赖）。
 * 防重复口径：next_fire_at 落库 + CAS 领发（UPDATE ... WHERE id=? AND next_fire_at=?），
 * 抢到才 submit——重启/扫描重叠都不双跑；下一次时间从"本次应发时刻"推进（不留漂移）。
 * 单实例假设：多实例部署需分布式锁（容量阶段兑现，已记台账）。
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final int MAX_PER_TICK = 50; // 单轮领发上限，防扫描风暴拖住调度线程

    private final JdbcTemplate jdbc;
    private final WorkflowCatalog catalog;
    private final ExecutionManager manager;

    public ScheduleService(JdbcTemplate jdbc, WorkflowCatalog catalog, ExecutionManager manager) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.manager = manager;
    }

    /** 登记/更新调度；返回下次触发（ISO 字符串，enabled=false 返回 null）。 */
    public String setSchedule(long workflowId, String cron, boolean enabled) {
        if (enabled) {
            CronExpression expr = parseCron(cron);
            ZonedDateTime next = expr.next(ZonedDateTime.now());
            if (next == null) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "cron 无未来触发点: " + cron);
            }
            jdbc.update("UPDATE workflow SET schedule_cron = ?, schedule_enabled = TRUE, next_fire_at = ?, "
                            + "update_time = CURRENT_TIMESTAMP, updater = ? WHERE id = ?",
                    cron, Timestamp.from(next.toInstant()), AuditActor.current(), workflowId);
            return next.toString();
        }
        jdbc.update("UPDATE workflow SET schedule_cron = NULL, schedule_enabled = FALSE, next_fire_at = NULL, "
                        + "update_time = CURRENT_TIMESTAMP, updater = ? WHERE id = ?",
                AuditActor.current(), workflowId);
        return null;
    }

    private static CronExpression parseCron(String cron) {
        try {
            CronExpression expr = CronExpression.parse(cron);
            if (expr == null) {
                throw new IllegalArgumentException("null expression");
            }
            return expr;
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "cron 表达式非法（需 6 段：秒 分 时 日 月 周，如 0 0/5 * * * *）: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${agentflow.schedule.tick-ms:15000}",
            initialDelayString = "${agentflow.schedule.tick-initial-ms:10000}")
    public void tick() {
        try {
            fireDue();
        } catch (Exception e) {
            log.error("调度扫描异常（不中断后续轮次）", e);
        }
    }

    /** 扫描到期并领发；返回本轮实际触发数（测试直接调用，免等 @Scheduled 周期）。 */
    public int fireDue() {
        List<DueRow> due = jdbc.query(
                "SELECT id, owner_id, schedule_cron, next_fire_at FROM workflow "
                        // deleted 再判一次是纵深防御：softDelete 已经关了 schedule_enabled，
                        // 但"逻辑删除的流水线还在定时烧钱"这种账不能只靠一处写入的正确性兜着。
                        + "WHERE deleted = FALSE AND schedule_enabled = TRUE AND next_fire_at IS NOT NULL "
                        + "AND next_fire_at <= CURRENT_TIMESTAMP ORDER BY next_fire_at LIMIT " + MAX_PER_TICK,
                (rs, i) -> new DueRow(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("schedule_cron"),
                        rs.getTimestamp("next_fire_at")));
        int fired = 0;
        for (DueRow row : due) {
            CronExpression expr;
            try {
                expr = CronExpression.parse(row.cron());
            } catch (IllegalArgumentException e) {
                log.error("库中 cron 非法，停用该调度 workflow={}", row.id());
                setScheduleQuietly(row.id());
                continue;
            }
            ZonedDateTime old = row.nextFireAt().toInstant().atZone(ZonedDateTime.now().getZone());
            ZonedDateTime next = expr.next(old);
            if (next == null) {
                setScheduleQuietly(row.id());
                continue;
            }
            int claimed = jdbc.update(
                    "UPDATE workflow SET next_fire_at = ?, update_time = CURRENT_TIMESTAMP, updater = ? "
                            + "WHERE id = ? AND next_fire_at = ?",
                    Timestamp.from(next.toInstant()), AuditActor.current(), row.id(), row.nextFireAt());
            if (claimed == 0) {
                continue; // 已被其它轮次/实例领走
            }
            try {
                WorkflowDefinition def = catalog.parseDefinition(row.id());
                manager.submit(row.id(), row.ownerId(), def, TraceIdUtils.generate());
                fired++;
                log.info("cron 触发 workflow={} traceId={}", row.id(), TraceIdUtils.current());
            } catch (Exception e) {
                log.error("cron 触发提交失败 workflow={}（next_fire_at 已推进，不卡死队列）", row.id(), e);
            }
        }
        return fired;
    }

    private void setScheduleQuietly(long workflowId) {
        try {
            jdbc.update("UPDATE workflow SET schedule_enabled = FALSE, next_fire_at = NULL, "
                    + "update_time = CURRENT_TIMESTAMP, updater = ? WHERE id = ?", AuditActor.current(), workflowId);
        } catch (Exception e) {
            log.warn("停用调度失败 workflow={}", workflowId, e);
        }
    }

    private record DueRow(long id, long ownerId, String cron, Timestamp nextFireAt) {
    }
}

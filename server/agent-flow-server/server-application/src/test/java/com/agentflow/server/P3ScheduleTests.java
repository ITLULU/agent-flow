package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.engine.model.WorkflowDefinition;
import com.agentflow.server.persistence.WorkflowCatalog;
import com.agentflow.server.schedule.ScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * cron 触发注册表：登记→回拨到期→fireDue 领发→执行落库；CAS 语义保证第二次扫描不重复触发。
 * 直接调 service（不依赖 @Scheduled 的真实节拍，测试确定性与 CI 友好）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p3-sched;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.task.scheduling.pool.size=1",
                // 把自动扫描节拍推到极远，避免与手动 fireDue() 抢领发（CAS 语义在测试里需确定）
                "agentflow.schedule.tick-ms=3600000",
                "agentflow.schedule.tick-initial-ms=3600000"})
class P3ScheduleTests {

    @Autowired
    ScheduleService schedule;

    @Autowired
    WorkflowCatalog catalog;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    private long newEchoWorkflow() throws Exception {
        WorkflowDefinition def = mapper.readValue("""
                {"name":"定时播报","nodes":[{"id":"e","type":"core.echo","params":{"message":"cron"}}],"edges":[]}""",
                WorkflowDefinition.class);
        return catalog.save(def, 0L);
    }

    @Test
    void registerBackdateFireThenNoDoubleFire() throws Exception {
        long id = newEchoWorkflow();
        String next = schedule.setSchedule(id, "0 0/5 * * * *", true);
        assertThat(next).isNotBlank();
        Long due = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow WHERE id = ? AND schedule_enabled = TRUE AND next_fire_at IS NOT NULL",
                Long.class, id);
        assertThat(due).isEqualTo(1L);

        // 非法 cron：A0400
        assertThatThrownBy(() -> schedule.setSchedule(id, "not a cron", true))
                .isInstanceOf(ApiException.class).hasMessageContaining("cron");

        // 回拨到期 → 领发恰好 1 次
        jdbc.update("UPDATE workflow SET next_fire_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), id);
        assertThat(schedule.fireDue()).isEqualTo(1);

        // 执行确实落库（引擎 submit 是异步的，轮询等待）
        long execCount = 0;
        for (int i = 0; i < 40; i++) {
            execCount = jdbc.queryForObject("SELECT COUNT(*) FROM execution WHERE workflow_id = ?", Long.class, id);
            if (execCount >= 1) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertThat(execCount).isEqualTo(1L);

        // 再扫一次：next_fire_at 已推进到未来，不得重复触发（CAS 领发语义）
        assertThat(schedule.fireDue()).isEqualTo(0);
    }

    @Test
    void unregisterStopsScheduling() throws Exception {
        long id = newEchoWorkflow();
        schedule.setSchedule(id, "0 0/5 * * * *", true);
        schedule.setSchedule(id, null, false);
        Long stillArmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow WHERE id = ? AND schedule_enabled = TRUE", Long.class, id);
        assertThat(stillArmed).isEqualTo(0L);
    }
}

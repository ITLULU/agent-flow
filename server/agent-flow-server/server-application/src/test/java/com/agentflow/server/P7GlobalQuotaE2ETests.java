package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.server.exec.ExecutionQuota;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨实例全局执行配额的端到端链路（评审 P7-3 #11）：真实 Spring 上下文 + 真实库 + 真实 HTTP。
 *
 * <p>这一层要证的不是算法（单测已经证了），而是**开关真的接上了**：
 * {@code global-quota.enabled=true} 时如果注入不到配额 Bean（@Nullable 依赖最常见的静默失效），
 * 提交照样 200、上限照样各数各的，而所有单测都会是绿的。
 * 所以这里的第一个用例就是"名额已被占满 ⇒ HTTP 必须 429"，它同时也是那条静默失效的反向验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-global-quota-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.exec.max-concurrent=2",
                "agentflow.exec.max-queued=0",
                "agentflow.exec.acquire-timeout=2s",
                "agentflow.exec.global-quota.enabled=true",
                "agentflow.exec.global-quota.max-concurrent=1",
                "agentflow.exec.global-quota.per-user-max-concurrent=0",
                "agentflow.exec.global-quota.lease-ttl=5m",
                "agentflow.exec.global-quota.renew-interval=30s",
                "agentflow.exec.global-quota.reserve-retry-interval=50ms"})
class P7GlobalQuotaE2ETests {

    private static final String DSL = """
            {"name":"配额探针","nodes":[{"id":"e","type":"core.echo","params":{"message":"hi"}}],"edges":[]}
            """;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExecutionQuota quota;

    @BeforeEach
    void cleanLeases() {
        jdbc.update("DELETE FROM execution_lease");
    }

    @Test
    void saturatedClusterRefusesSubmissionAndFreesCapacityOnRelease() {
        long workflowId = createWorkflow();
        int before = executionCount();

        // 占满唯一的全集群名额：等价于"另一个实例正在跑一条"
        assertThat(quota.tryReserve(900_001L, 42L)).isTrue();

        ResponseEntity<String> rejected = submit(workflowId);
        assertThat(rejected.getStatusCode().value()).isEqualTo(429);
        assertThat(rejected.getBody()).contains("A0429").contains("配额");
        // 拒绝必须发生在建记录之前：否则每个被拒的提交都留下一条永远 RUNNING 的僵尸
        assertThat(executionCount()).isEqualTo(before);

        quota.release(900_001L);
        String detail = executeAndPoll(workflowId, 15);
        assertThat(detail).contains("SUCCESS");
        // 跑完归还名额：忘记 release 的话，配额会被执行次数而不是并发数耗尽
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execution_lease", Integer.class)).isZero();
    }

    @Test
    void staleLeaseFromCrashedInstanceNeitherBlocksNorNeedsManualCleanup() {
        long workflowId = createWorkflow();
        // 崩溃实例留下的过期租约：既不释放也不续租。若上限按"表里有行"计算，这条提交会被永久饿死
        jdbc.update("INSERT INTO execution_lease (execution_id, owner_id, instance_id, acquired_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                900_002L, 7L, "crashed-instance",
                Timestamp.from(Instant.now().minusSeconds(120)), Timestamp.from(Instant.now().minusSeconds(60)));

        String detail = executeAndPoll(workflowId, 15);

        assertThat(detail).contains("SUCCESS");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_lease WHERE instance_id = 'crashed-instance'", Integer.class))
                .isZero(); // 真正预留时被顺手回收，不需要运维手工删行
    }

    private long createWorkflow() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = rest.exchange("/api/workflows", HttpMethod.POST,
                new HttpEntity<>(DSL, headers), String.class).getBody();
        assertThat(body).isNotNull();
        return extractLong(body, "workflowId");
    }

    private ResponseEntity<String> submit(long workflowId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/workflows/" + workflowId + "/executions", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
    }

    private int executionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM execution", Integer.class);
    }

    private String executeAndPoll(long workflowId, int maxSeconds) {
        ResponseEntity<String> resp = submit(workflowId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        long executionId = extractLong(resp.getBody(), "executionId");
        for (int i = 0; i < maxSeconds * 5; i++) {
            String detail = rest.getForObject("/api/executions/" + executionId, String.class);
            if (detail != null && (detail.contains("SUCCESS") || detail.contains("FAILED"))) {
                return detail;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("执行未在 " + maxSeconds + "s 内到达终态");
    }

    private long extractLong(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        int start = idx + field.length() + 2;
        while (start < json.length() && !Character.isDigit(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}

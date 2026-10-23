package com.agentflow.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.server.persistence.JdbcContentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P7-3#12b 最后一格：证的是**后台扫描器自己会动**。
 *
 * {@code ArtifactSyncE2ETests} 刻意把后台扫描推迟到一小时后，所有同步都由测试手工 sweep/POST 触发——
 * 那是为了做失败注入时必须独占节拍。但"生产上没人去点 /sync，成品到底会不会上去"这个问题它答不了，
 * 而 #12 的验收承诺恰恰写的是"自动补齐远端副本"。这里就用真的 @Scheduled 节拍回答它。
 *
 * 纪律：本类**一次也不调用** sync()/sweep()。所有状态推进都必须由定时器做出，
 * 否则测的就只是一个方法调用，不是调度。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-sweep-sched;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=false",
                "agentflow.content.storage.sweep-interval-ms=400",
                "agentflow.content.storage.sweep-initial-delay-ms=200"
        })
class ArtifactSweepSchedulingTests {

    private static final String BUCKET = "af-sweep-sched";
    private static StubS3 s3;

    @TempDir
    static Path library;

    @DynamicPropertySource
    static void stubEndpoints(DynamicPropertyRegistry registry) throws Exception {
        s3 = new StubS3();
        registry.add("agentflow.content.library-dir", () -> library.toString());
        registry.add("agentflow.content.storage.enabled", () -> "true");
        registry.add("agentflow.content.storage.endpoint", s3::base);
        registry.add("agentflow.content.storage.bucket", () -> BUCKET);
        registry.add("agentflow.content.storage.access-key", () -> "sched-ak");
        registry.add("agentflow.content.storage.secret-key", () -> "sched-secret-key");
    }

    @AfterAll
    static void stopStub() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Autowired
    JdbcContentStore store;

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void resetStub() throws IOException {
        try (var dirs = Files.list(library)) {
            for (Path p : dirs.toList()) {
                if (Files.isDirectory(p)) {
                    try (var walk = Files.walk(p)) {
                        for (Path f : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                            Files.deleteIfExists(f);
                        }
                    }
                }
            }
        }
        s3.objects.clear();
        s3.failWrites = false;
        s3.reportMissing = false;
    }

    /** 造一条"本地已完整"的成品：库里一行 + 盘上两个文件，正是 sweep 的入选条件。 */
    private long newCompleteArtifact(String title) {
        long id = store.insert(1L, "article", title, "正文 " + title, "{\"tags\":\"sched\"}");
        store.writeText(id, "article.md", "# " + title + "\n正文");
        store.writeText(id, "meta.json", "{\"title\":\"" + title + "\"}");
        store.markComplete(id);
        assertThat(str(store.find(id).get("sync_state"))).isEqualTo("local");
        return id;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 轮询等状态翻转；超时就把"当前状态 + 桩里收到的请求"一起打出来，方便区分是没扫到还是扫了没成。 */
    private Map<String, Object> awaitSyncState(long id, String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<String, Object> row = Map.of();
        while (System.currentTimeMillis() < deadline) {
            row = store.find(id);
            if (expected.equals(str(row.get("sync_state")))) {
                return row;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        return row;
    }

    @Test
    void backgroundSweeperSyncsWithoutAnyManualCall() throws Exception {
        long id = newCompleteArtifact("定时扫描自动同步");

        Map<String, Object> row = awaitSyncState(id, "synced", 20_000);

        assertThat(str(row.get("sync_state")))
                .as("后台定时器应自己把它同步掉（本测试从不调用 sync()/sweep()）；桩侧请求=%s", s3.log)
                .isEqualTo("synced");
        assertThat(row.get("synced_at")).isNotNull();
        assertThat(str(row.get("sync_error"))).isEmpty();
        // 真的两个文件都上去了，不是只写了个状态
        assertThat(s3.objects.keySet()).anyMatch(k -> k.endsWith("article.md"))
                .anyMatch(k -> k.endsWith("meta.json"));
        assertThat(s3.bucketCreated()).isTrue();
    }

    /**
     * 反向验证（这一条才是本类的重点）：备份面故障时，@Scheduled 每一轮都要如实把状态打成 failed，
     * 但**不能**把服务自己带倒；故障解除后下一轮必须自己长回来，仍然不调用任何手动入口。
     */
    @Test
    void sweeperRecordsFailureAndHealsOnLaterTick() throws Exception {
        s3.failWrites = true;
        long id = newCompleteArtifact("写被拒时应记失败");

        Map<String, Object> failed = awaitSyncState(id, "failed", 20_000);
        assertThat(str(failed.get("sync_state"))).isEqualTo("failed");
        assertThat(str(failed.get("sync_error"))).isNotEmpty();
        // 备份面坏了，发布面不能被带倒：file_state 仍是 complete
        assertThat(str(failed.get("file_state"))).isEqualTo("complete");
        // 主链路仍可用：readiness 与成品清单接口都该正常回
        ResponseEntity<String> readiness = rest.getForEntity("/actuator/health/readiness", String.class);
        assertThat(readiness.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(rest.getForEntity("/api/artifacts?limit=5", String.class).getStatusCode().is2xxSuccessful())
                .isTrue();

        s3.failWrites = false;
        Map<String, Object> healed = awaitSyncState(id, "synced", 20_000);
        assertThat(str(healed.get("sync_state")))
                .as("失败态仍在 pendingForSweep 的选中范围内，下一轮该自愈").isEqualTo("synced");
        assertThat(str(healed.get("sync_error"))).isEmpty();
    }
}

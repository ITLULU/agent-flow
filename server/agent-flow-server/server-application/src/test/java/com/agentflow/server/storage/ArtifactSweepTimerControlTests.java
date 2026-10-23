package com.agentflow.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.server.persistence.JdbcContentStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code ArtifactSweepSchedulingTests} 的对照组——用它自己证明"是定时器在干活"。
 *
 * 同一套装配、同一个桩，只把扫描节拍推到一小时后。若这条也能变成 synced，
 * 就说明同步是发布路径或别处顺手做的，那"定时器驱动"的结论就得推翻。
 * 反向验证不靠改生产代码，靠这个镜像上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-sweep-slow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=false",
                // 与调度组同参数，只这一处不同：节拍远大于观察窗口
                "agentflow.content.storage.sweep-interval-ms=3600000",
                "agentflow.content.storage.sweep-initial-delay-ms=3600000"
        })
class ArtifactSweepTimerControlTests {

    private static StubS3 s3;

    @TempDir
    static Path library;

    @DynamicPropertySource
    static void stubEndpoints(DynamicPropertyRegistry registry) throws Exception {
        s3 = new StubS3();
        registry.add("agentflow.content.library-dir", () -> library.toString());
        registry.add("agentflow.content.storage.enabled", () -> "true");
        registry.add("agentflow.content.storage.endpoint", s3::base);
        registry.add("agentflow.content.storage.bucket", () -> "af-sweep-slow");
        registry.add("agentflow.content.storage.access-key", () -> "ctl-ak");
        registry.add("agentflow.content.storage.secret-key", () -> "ctl-secret-key");
    }

    @org.junit.jupiter.api.AfterAll
    static void stopStub() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Autowired
    JdbcContentStore store;

    @Test
    void withoutTheTimerTheArtifactStaysLocal() throws Exception {
        long id = store.insert(1L, "article", "慢节拍对照", "正文", "{}");
        store.writeText(id, "article.md", "# 慢节拍对照");
        store.markComplete(id);

        TimeUnit.SECONDS.sleep(4);

        Map<String, Object> row = store.find(id);
        assertThat(String.valueOf(row.get("sync_state"))).isEqualTo("local");
        assertThat(s3.objects.keySet()).isEmpty();
    }
}

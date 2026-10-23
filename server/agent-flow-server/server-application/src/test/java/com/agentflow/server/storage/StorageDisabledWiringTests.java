package com.agentflow.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.server.config.StorageConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * #12b 的"关"这一半：默认关必须是真的关——不建客户端、不排扫描，一个字节也不出网。
 *
 * 只断言配置值为 false 是不够的：那挡不住"开关关着但 bean 照样装配、照样往一个空端点上重试"。
 * 所以这里断言的是 bean 根本不存在，扫描器与 ObjectStore 都查无此Bean。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-storage-off;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=false"
        })
class StorageDisabledWiringTests {

    @Autowired
    ApplicationContext context;

    @Autowired
    ArtifactSyncService syncService;

    @Test
    void defaultOffWiresNoClientAndNoSweeper() {
        assertThat(context.getBeanNamesForType(StorageConfig.ArtifactSyncSweeper.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ObjectStore.class)).isEmpty();
        assertThat(syncService.enabled()).isFalse();
    }
}

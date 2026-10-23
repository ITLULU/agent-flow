package com.agentflow.server.config;

import com.agentflow.server.storage.ArtifactSyncService;
import com.agentflow.server.storage.ObjectStore;
import com.agentflow.server.storage.S3ObjectStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 对象存储装配（P7-3#12b）。整条链路默认关：不开就不建客户端、不排扫描、一个字节也不出网。
 *
 * 开启后建桶探针失败**不让进程退出**——备份面不可用不应该把创作主链路一起拖倒；
 * 但会把 ERROR 与指标钉在启动日志里，"配置了远端却静默不同步"是最难查的那类故障。
 */
@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "agentflow.content.storage", name = "enabled", havingValue = "true")
    ObjectStore objectStore(ObjectStorageProperties props, MeterRegistry registry) {
        S3ObjectStore store = new S3ObjectStore(props);
        try {
            store.ensureBucket();
            log.info("成品对象存储就绪: bucket={} prefix={}（凭证不回显）", props.bucket(), props.keyPrefix());
        } catch (RuntimeException e) {
            Counter.builder("agentflow.artifact.sync").tag("state", "bucket_unavailable")
                    .register(registry).increment();
            log.error("成品对象存储不可用（同步会失败但主链路不受影响）: bucket={} 原因={}",
                    props.bucket(), e.getMessage());
        }
        return store;
    }

    @Bean
    @ConditionalOnProperty(prefix = "agentflow.content.storage", name = "enabled", havingValue = "true")
    ArtifactSyncSweeper artifactSyncSweeper(ArtifactSyncService service) {
        return new ArtifactSyncSweeper(service);
    }

    /** 兜底扫描：publish.local 只保证本地落盘，远端副本由这里补齐；手动入口是 POST /api/artifacts/{id}/sync。 */
    public static final class ArtifactSyncSweeper {

        private static final int BATCH = 20;

        private final ArtifactSyncService service;

        ArtifactSyncSweeper(ArtifactSyncService service) {
            this.service = service;
        }

        @Scheduled(fixedDelayString = "${agentflow.content.storage.sweep-interval-ms:60000}",
                initialDelayString = "${agentflow.content.storage.sweep-initial-delay-ms:15000}")
        public void sweep() {
            int ok = service.sweep(BATCH);
            if (ok > 0) {
                log.info("成品对象存储扫描：本轮同步成功 {} 条", ok);
            }
        }
    }
}

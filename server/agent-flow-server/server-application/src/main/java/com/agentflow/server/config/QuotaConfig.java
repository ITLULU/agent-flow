package com.agentflow.server.config;

import com.agentflow.server.exec.ExecutionQuota;
import com.agentflow.server.persistence.JdbcExecutionQuota;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 跨实例执行配额装配（评审 P7-3 #11）。默认关：单实例形态下本地闸门就是全局上限，
 * 多引一张表、多一条事务只增加故障点，不增加任何保证。
 */
@Configuration
public class QuotaConfig {

    @Bean
    @ConditionalOnProperty(prefix = "agentflow.exec.global-quota", name = "enabled", havingValue = "true")
    ExecutionQuota executionQuota(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                  ExecProperties props, MeterRegistry registry) {
        return new JdbcExecutionQuota(jdbc, txManager, props.globalQuota(), deriveInstanceId(), registry);
    }

    /** 实例标识：pid@host 已能区分同机两个 JVM，再加 6 位随机串兜住容器复用 pid 的情况。只用于对账，不含秘密。 */
    static String deriveInstanceId() {
        String pidHost = ManagementFactory.getRuntimeMXBean().getName().replace('@', '-');
        return (pidHost + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6))
                .replace('/', '-');
    }
}

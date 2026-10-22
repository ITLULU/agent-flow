package com.agentflow.server.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台鉴权/限流配置（P3 起：JWT 用户体系替换 P0 骨架的 API Token 单通道）。
 * enabled/prod 口径在 README 配置表与文档三处一致；jwt-secret 只从 env 注入，禁止写进 yml 明文。
 */
@ConfigurationProperties(prefix = "agentflow.security")
public record SecurityProperties(
        boolean enabled,
        String apiToken,        // 服务通道（运维/内部调用），可空
        String jwtSecret,       // AGENTFLOW_JWT_SECRET；enabled=true 且为空 → 启动失败
        Duration tokenTtl,      // JWT 有效期，默认 2h
        Integer rateLimitPerMinute, // per-principal 每分钟请求上限；显式 0=关闭限流，缺省/负数=120
        String masterKey,           // AGENTFLOW_MASTER_KEY（base64 32B）；生产仍只从 env 注入，进属性仅为测试可覆盖
        String bootstrapAdminUsername,  // AGENTFLOW_BOOTSTRAP_ADMIN_USERNAME：启动时创建/保留的管理员账号；空=不引导
        String bootstrapAdminPassword   // AGENTFLOW_BOOTSTRAP_ADMIN_PASSWORD：只在创建新账号时使用，永不打印
) {

    public SecurityProperties {
        if (apiToken == null) {
            apiToken = "";
        }
        if (jwtSecret == null) {
            jwtSecret = "";
        }
        if (masterKey == null) {
            masterKey = "";
        }
        if (bootstrapAdminUsername == null) {
            bootstrapAdminUsername = "";
        }
        if (bootstrapAdminPassword == null) {
            bootstrapAdminPassword = "";
        }
        if (tokenTtl == null) {
            tokenTtl = Duration.ofHours(2);
        }
        if (rateLimitPerMinute == null || rateLimitPerMinute < 0) {
            rateLimitPerMinute = 120;
        }
    }
}

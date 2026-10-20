package com.agentflow.server.security;

import com.agentflow.common.security.AesGcmCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 认证/加密 Bean 装配。两个密钥的降级策略不同是刻意的：
 * - JWT：enabled=false 时可用 dev fallback（本地开发零配置）；enabled=true 且密钥缺失由 StartupSelfCheck 拒绝启动。
 * - 主密钥：缺失时凭证库 Bean 为 NullBean（NullBean 模式同 P2 LlmGateway），凭证 API 报配置错，其它功能不受影响。
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class AuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);
    private static final String DEV_FALLBACK_SECRET = "agentflow-dev-jwt-secret-0123456789abcdef";

    @Bean
    public JwtCodec jwtCodec(SecurityProperties props, ObjectMapper mapper) {
        String secret = props.jwtSecret();
        if (secret.isBlank()) {
            log.warn("agentflow.security.jwt-secret 未配置，使用 dev fallback——生产（enabled=true）禁止此状态，StartupSelfCheck 会拦截");
            secret = DEV_FALLBACK_SECRET;
        }
        return new JwtCodec(secret.getBytes(StandardCharsets.UTF_8), mapper, props.tokenTtl());
    }

    /** 主密钥缺失 → NullBean：凭证 API 每次调用检查并报 A0400，不阻塞其它功能启动。 */
    @Bean
    public AesGcmCipher credentialCipher(SecurityProperties props) {
        String master = props.masterKey();
        if (master.isBlank()) {
            return null;
        }
        try {
            return new AesGcmCipher(AesGcmCipher.decodeKey(master));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IllegalStateException("AGENTFLOW_MASTER_KEY 非法（需 base64 的 32 字节）: " + e.getMessage());
        }
    }
}

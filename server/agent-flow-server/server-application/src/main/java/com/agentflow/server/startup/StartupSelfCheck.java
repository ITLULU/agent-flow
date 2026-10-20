package com.agentflow.server.startup;

import com.agentflow.server.security.SecurityProperties;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动自检（skill §1/§2 DoD）：打印生效 profile、关键开关、密钥环境变量就位情况。
 * 只报"存在与否"，永不打印密钥值。
 */
@Component
public class StartupSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSelfCheck.class);

    private final Environment env;
    private final SecurityProperties securityProps;

    public StartupSelfCheck(Environment env, SecurityProperties securityProps) {
        this.env = env;
        this.securityProps = securityProps;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== agent-flow 启动自检 ===");
        log.info("active profiles : {}", Arrays.toString(env.getActiveProfiles()));
        log.info("server port     : {}", env.getProperty("server.port", "8080"));
        log.info("security.enabled: {}", securityProps.enabled());
        log.info("AGENTFLOW_MASTER_KEY present : {}", !securityProps.masterKey().isBlank());
        log.info("AGENTFLOW_API_TOKEN present  : {}", !securityProps.apiToken().isBlank());
        log.info("jwt-secret present           : {}", !securityProps.jwtSecret().isBlank());
        log.info("rate-limit per-minute        : {}", securityProps.rateLimitPerMinute());
        // P3 硬基线：开鉴权必须配 JWT 密钥——缺密钥的"开鉴权"等于把所有请求关在门外或全部放行，两者都不可接受
        if (securityProps.enabled() && securityProps.jwtSecret().isBlank()) {
            throw new IllegalStateException("security.enabled=true 但未配置 AGENTFLOW_JWT_SECRET（fail-fast，禁止默认密钥上线）");
        }
        if ("prod".equals(String.join(",", env.getActiveProfiles())) && !securityProps.enabled()) {
            log.error("!! prod profile 下 security.enabled=false，违反安全基线，禁止对外服务 !!");
        }
    }
}

package com.agentflow.server.security;

import com.agentflow.common.security.PasswordHasher;
import com.agentflow.server.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 管理员引导（P7-2a）：admin 只能由运维显式注入的 bootstrap 账号产生，注册接口永不授予。
 * 口令只在"账号尚不存在"时使用一次，任何日志都不带口令；空口令按未配置处理（与 *_FILE 空秘密口径一致）。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final SecurityProperties securityProps;

    public AdminBootstrap(UserRepository users, SecurityProperties securityProps) {
        this.users = users;
        this.securityProps = securityProps;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = securityProps.bootstrapAdminUsername().trim();
        String password = securityProps.bootstrapAdminPassword();
        if (username.isEmpty()) {
            if (users.count() == 0) {
                log.warn("无管理员：未配置 AGENTFLOW_BOOTSTRAP_ADMIN_USERNAME，且 users 表为空。"
                        + "注册接口只产普通用户，需要 admin 请配置 bootstrap 账号后重启。");
            }
            return;
        }
        var existing = users.findByUsername(username);
        if (existing.isPresent()) {
            log.info("bootstrap admin 已存在: {} (role={})，不做改动", username, existing.get().role());
            return;
        }
        if (password.isEmpty()) {
            log.warn("bootstrap admin 用户名已配置但口令为空，按未配置处理：{}", username);
            return;
        }
        if (password.length() < 8 || password.length() > 64) {
            throw new IllegalStateException(
                    "AGENTFLOW_BOOTSTRAP_ADMIN_PASSWORD 长度需为 8-64 位（当前 " + password.length() + " 位）");
        }
        long uid = users.insert(username, PasswordHasher.hash(password), Principal.ROLE_ADMIN);
        log.info("bootstrap admin 已创建: {} (uid={}, role={})", username, uid, Principal.ROLE_ADMIN);
    }
}

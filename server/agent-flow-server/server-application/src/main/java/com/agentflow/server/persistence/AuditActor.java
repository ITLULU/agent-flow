package com.agentflow.server.persistence;

import com.agentflow.server.security.Principal;
import com.agentflow.server.security.PrincipalContext;

/**
 * 审计列（creator/updater）取当前操作人：请求线程取登录主体用户名；
 * 调度/引擎等后台线程没有请求主体（ThreadLocal 不跨线程），统一落 'system'。
 */
public final class AuditActor {

    public static final String SYSTEM = "system";

    private AuditActor() {
    }

    public static String current() {
        Principal p = PrincipalContext.peek();
        if (p == null || p.username() == null || p.username().isBlank()) {
            return SYSTEM;
        }
        return p.username();
    }
}

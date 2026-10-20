package com.agentflow.server.security;

/**
 * 当前请求主体：由 AuthFilter 解析后放入 {@link PrincipalContext}。
 * uid=0 保留给系统（enabled=false 匿名）/服务通道（X-API-Token）。
 */
public record Principal(long uid, String username, String role) {

    public static final long SYSTEM_UID = 0L;
    public static final String ROLE_ADMIN = "admin";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role) || uid == SYSTEM_UID;
    }

    /** 资源归属判定：admin（含系统匿名）不限，普通用户只能碰自己的。 */
    public boolean canAccess(long ownerId) {
        return isAdmin() || uid == ownerId;
    }
}

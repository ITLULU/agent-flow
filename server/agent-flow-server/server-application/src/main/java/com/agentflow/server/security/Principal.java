package com.agentflow.server.security;

/**
 * 当前请求主体：由 AuthFilter 解析后放入 {@link PrincipalContext}。
 * uid=0 保留给系统（enabled=false 匿名）/服务通道（X-API-Token），它只是"这批资源的归属者"，不是特权。
 */
public record Principal(long uid, String username, String role) {

    public static final long SYSTEM_UID = 0L;
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_OPS = "ops";
    public static final String ROLE_ANONYMOUS = "anonymous";
    /** 抓取专用主体：只用于 /actuator/prometheus，不具备任何业务资源访问能力。 */
    public static final String ROLE_METRICS = "metrics";

    /** 特权只看角色，不看 uid：uid=0 是服务通道/匿名的归属键，把它当管理员等于"一把静态 token 开全站"（评审 P6 #2）。 */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    /** 限流分桶用：匿名没有身份可依赖，只能按客户端 IP 记数（评审 P6 #6）。 */
    public boolean isAnonymous() {
        return uid == SYSTEM_UID && ROLE_ANONYMOUS.equals(role);
    }

    /** 资源归属判定：admin 不限，其余（含 service/匿名）只能碰自己 uid 名下的。 */
    public boolean canAccess(long ownerId) {
        return isAdmin() || uid == ownerId;
    }
}

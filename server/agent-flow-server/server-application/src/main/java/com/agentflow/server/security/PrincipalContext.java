package com.agentflow.server.security;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;

/**
 * 请求级主体上下文：AuthFilter 置入、Controller/Service 读取、filter finally 清理。
 * 只在同步请求线程使用（虚拟线程下 ThreadLocal 生命周期与 filter 对齐，无跨线程携带场景——执行提交前就完成鉴权）。
 */
public final class PrincipalContext {

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private PrincipalContext() {
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal get() {
        Principal p = HOLDER.get();
        if (p == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "请求缺少认证主体");
        }
        return p;
    }

    /** 可选读取：用于匿名可达路径上的旁路逻辑（如审计拦截器），缺主体不抛错。 */
    public static Principal peek() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

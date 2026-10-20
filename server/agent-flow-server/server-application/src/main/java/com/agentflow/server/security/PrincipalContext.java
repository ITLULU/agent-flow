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

    public static void clear() {
        HOLDER.remove();
    }
}

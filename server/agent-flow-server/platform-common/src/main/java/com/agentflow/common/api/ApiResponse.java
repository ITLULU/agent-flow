package com.agentflow.common.api;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.trace.TraceIdUtils;

/**
 * 统一响应结构：code / message / data / traceId。
 * 错误响应禁止携带堆栈；traceId 取当前 MDC，与日志可互查。
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data, TraceIdUtils.current());
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String detail) {
        String message = (detail == null || detail.isBlank()) ? errorCode.defaultMessage()
                : errorCode.defaultMessage() + ": " + detail;
        return new ApiResponse<>(errorCode.code(), message, null, TraceIdUtils.current());
    }
}

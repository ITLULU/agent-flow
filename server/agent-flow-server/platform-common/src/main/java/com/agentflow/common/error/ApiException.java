package com.agentflow.common.error;

/** 业务异常：抛出后由全局 advice 转成结构化错误响应，禁止裸堆栈出网。 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String detail) {
        super(errorCode.defaultMessage() + ": " + detail);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

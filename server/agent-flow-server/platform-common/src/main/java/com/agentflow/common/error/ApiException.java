package com.agentflow.common.error;

/** 业务异常：抛出后由全局 advice 转成结构化错误响应，禁止裸堆栈出网。 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.detail = null;
    }

    public ApiException(ErrorCode errorCode, String detail) {
        super(errorCode.defaultMessage() + ": " + detail);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 未拼接前缀的原始原因：响应组装只认这里，否则默认文案会被拼两遍。 */
    public String detail() {
        return detail;
    }
}

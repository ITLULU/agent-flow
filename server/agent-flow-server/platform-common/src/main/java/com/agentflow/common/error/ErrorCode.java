package com.agentflow.common.error;

/**
 * 统一错误码（A=客户端/入口错，B=系统内部错，C=外部依赖错，参照行业分段惯例）。
 * 新增错误码必须在此登记，禁止在业务代码里裸写字符串 code。
 */
public enum ErrorCode {

    OK("0", "成功"),
    BAD_REQUEST("A0400", "请求参数不合法"),
    UNAUTHORIZED("A0401", "未认证或凭证无效"),
    FORBIDDEN("A0403", "无权限访问该资源"),
    NOT_FOUND("A0404", "资源不存在"),
    METHOD_NOT_ALLOWED("A0405", "请求方法不支持"),
    RATE_LIMITED("A0429", "请求过于频繁"),
    INTERNAL("B0001", "系统内部错误"),
    UPSTREAM_REJECTED("C0001", "外部依赖拒绝连接"),
    UPSTREAM_TIMEOUT("C0002", "外部依赖调用超时"),
    UPSTREAM_PROTOCOL_ERROR("C0003", "外部依赖协议异常"),
    DEPENDENCY_UNAVAILABLE("C0004", "外部依赖熔断或并发舱壁已满"),
    WORKFLOW_INVALID("C0500", "工作流定义不合法");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}

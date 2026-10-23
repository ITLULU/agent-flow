package com.agentflow.server.security;

/**
 * API 数据敏感级别（评审 P7-3#14：路由必须有显式标记，鉴权收口才有清单可查）。
 * PUBLIC 仅限匿名可达入口；SENSITIVE 的访问会写审计日志（不记录请求体/密钥值）。
 */
public enum Sensitivity {
    PUBLIC,
    USER,
    SENSITIVE
}

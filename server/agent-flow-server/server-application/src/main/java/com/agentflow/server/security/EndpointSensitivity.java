package com.agentflow.server.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 API 的数据敏感级别。可标在方法（精确）或控制器类（兜底）上；
 * 启动时 EndpointSensitivityCatalog 会扫描全部路由，缺失标记直接拒绝启动，防止新端点绕开分类。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EndpointSensitivity {
    Sensitivity value();
}

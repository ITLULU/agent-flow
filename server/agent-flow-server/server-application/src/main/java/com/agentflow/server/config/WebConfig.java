package com.agentflow.server.config;

import com.agentflow.server.security.SensitiveAccessAuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SensitiveAccessAuditInterceptor sensitiveAccessAudit;

    public WebConfig(SensitiveAccessAuditInterceptor sensitiveAccessAudit) {
        this.sensitiveAccessAudit = sensitiveAccessAudit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sensitiveAccessAudit).addPathPatterns("/api/**");
    }
}

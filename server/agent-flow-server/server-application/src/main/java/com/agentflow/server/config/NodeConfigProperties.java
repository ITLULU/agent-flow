package com.agentflow.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 节点适配器配置（agentflow.nodes.*）：全部凭证 env 注入、默认空串——
 * 空凭证不阻塞启动、节点照常注册（画布可存 DSL），执行时刻才报不可重试的配置错误。
 * P3 加密凭证库落地后，用户级凭证迁移至此表之下，本类只留系统级兜底。
 */
@ConfigurationProperties(prefix = "agentflow.nodes")
public record NodeConfigProperties(Llm llm, Image image, Wechat wechat) {

    public record Llm(String apiKey, String defaultModel) {
    }

    public record Image(String apiKey, String baseUrl) {
    }

    public record Wechat(String appId, String appSecret, String baseUrl) {
    }

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

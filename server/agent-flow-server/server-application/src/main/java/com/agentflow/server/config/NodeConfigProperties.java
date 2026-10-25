package com.agentflow.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 节点适配器配置（agentflow.nodes.*）：全部凭证 env 注入、默认空串——
 * 空凭证不阻塞启动、节点照常注册（画布可存 DSL），执行时刻才报不可重试的配置错误。
 * P3 加密凭证库落地后，用户级凭证迁移至此表之下，本类只留系统级兜底。
 */
@ConfigurationProperties(prefix = "agentflow.nodes")
public record NodeConfigProperties(Llm llm, Image image, Wechat wechat) {

    /**
     * baseUrl 只填 host（如 https://dashscope.aliyuncs.com）：SDK 的协议 path 是固定的，
     * 带 /compatible-mode 之类后缀会拼成 404。空=用各客户端内置默认。
     */
    public record Llm(String apiKey, String defaultModel, String baseUrl) {
    }

    public record Image(String apiKey, String baseUrl) {
    }

    public record Wechat(String appId, String appSecret, String baseUrl) {
    }

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

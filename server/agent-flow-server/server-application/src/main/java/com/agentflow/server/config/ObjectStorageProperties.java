package com.agentflow.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 成品库对象存储同步配置（agentflow.content.storage.*，评审 P7-3#12b）。
 *
 * 默认关：本地卷仍是第一交付面，"配了但没连上"不该把服务起不来变成常态——关着时同步链路零调用。
 * 开启后端点/桶/密钥必须齐（fail-fast），否则会出现"看起来开了，其实每条都在失败"的静默降级。
 * 凭证只从环境变量注入（compose 用 *_FILE 挂进容器再导出），仓库与日志里永不出现密钥。
 */
@ConfigurationProperties(prefix = "agentflow.content.storage")
public record ObjectStorageProperties(Boolean enabled, String endpoint, String bucket, String region,
        String accessKey, String secretKey, String keyPrefix, Integer connectTimeoutMs, Integer requestTimeoutMs) {

    public ObjectStorageProperties {
        enabled = enabled == null ? Boolean.FALSE : enabled;
        region = NodeConfigProperties.blank(region) ? "us-east-1" : region.trim();
        keyPrefix = NodeConfigProperties.blank(keyPrefix) ? "artifacts" : trimSlashes(keyPrefix);
        connectTimeoutMs = connectTimeoutMs == null ? 5000 : connectTimeoutMs;
        requestTimeoutMs = requestTimeoutMs == null ? 30000 : requestTimeoutMs;
        if (enabled) {
            if (NodeConfigProperties.blank(endpoint) || NodeConfigProperties.blank(bucket)
                    || NodeConfigProperties.blank(accessKey) || NodeConfigProperties.blank(secretKey)) {
                throw new IllegalArgumentException(
                        "agentflow.content.storage 已开启但配置不全：endpoint/bucket/access-key/secret-key 都必填"
                                + "（密钥只走环境变量，不写进 yml 与 compose 明文）");
            }
            if (!(endpoint.trim().startsWith("http://") || endpoint.trim().startsWith("https://"))) {
                throw new IllegalArgumentException(
                        "agentflow.content.storage.endpoint 必须带 scheme（http:// 或 https://），当前: " + endpoint);
            }
            if (connectTimeoutMs < 100 || requestTimeoutMs < 100) {
                throw new IllegalArgumentException(
                        "agentflow.content.storage 的两个超时都必须 >= 100ms，当前 connect=" + connectTimeoutMs
                                + " request=" + requestTimeoutMs);
            }
        }
    }

    private static String trimSlashes(String s) {
        String v = s.trim();
        while (v.startsWith("/")) {
            v = v.substring(1);
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v.isEmpty() ? "artifacts" : v;
    }
}

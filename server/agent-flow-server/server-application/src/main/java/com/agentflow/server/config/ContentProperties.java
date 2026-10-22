package com.agentflow.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内容成品库配置（agentflow.content.*）。
 * library-dir 是产物目录的白名单根：所有读写路径都必须经 JdbcContentStore.resolveInside 收敛，
 * 相对路径按进程工作目录解析，容器部署时应显式挂到持久卷。
 */
@ConfigurationProperties(prefix = "agentflow.content")
public record ContentProperties(String libraryDir) {

    public ContentProperties {
        libraryDir = NodeConfigProperties.blank(libraryDir) ? "./data/library" : libraryDir;
    }
}

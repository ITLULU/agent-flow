package com.agentflow.nodes.spi;

import java.util.Map;
import java.util.Optional;
/**
 * 凭证解析端口（纯 Java，node-adapters 侧定义）：credentialId → 明文键值对。
 * 实现在 server-application（JDBC + AES-GCM 解密）——节点不感知存储与加密，依赖方向保持 server→node-adapters。
 * 约定：解析失败/不存在返回 Optional.empty()，节点映射为不可重试配置错；端口实现绝不写日志输出秘密。
 */
public interface CredentialProvider {

    Optional<Map<String, String>> resolve(long credentialId);
}

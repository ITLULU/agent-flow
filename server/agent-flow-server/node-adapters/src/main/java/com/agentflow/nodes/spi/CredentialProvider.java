package com.agentflow.nodes.spi;

import java.util.Map;
import java.util.Optional;
/**
 * 凭证解析端口（纯 Java，node-adapters 侧定义）：(credentialId, workflowId) → 明文键值对。
 * 实现在 server-application（JDBC + AES-GCM 解密）——节点不感知存储与加密，依赖方向保持 server→node-adapters。
 * 授权口径：workflowId 必传，实现方须校验"该工作流的归属者 == 该凭证的归属者"，
 * 否则任何用户都能在自己的 DSL 里填别人的 credentialId 借用别人的密钥（评审 P6 #1）。
 * 约定：不存在/不属于本流/解密失败一律回 Optional.empty()（同一种失败面容，避免探测他人凭证是否存在）；
 * 端口实现绝不写日志输出秘密。
 */
public interface CredentialProvider {

    Optional<Map<String, String>> resolve(long credentialId, long workflowId);
}

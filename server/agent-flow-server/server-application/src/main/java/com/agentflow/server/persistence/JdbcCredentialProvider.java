package com.agentflow.server.persistence;

import com.agentflow.common.security.AesGcmCipher;
import com.agentflow.nodes.spi.CredentialProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * CredentialProvider 端口的服务端实现：归属校验 → 密文读取 → AES-GCM 解密 → 键值对。
 * 失败一律回 Optional.empty()（日志只报 id 与异常类型，永不带出秘密内容）——节点侧映射为不可重试配置错。
 * 归属校验是硬门禁：凭证 id 来自用户可写的 DSL，不校验就是"任何人可用他人密钥发他人公众号草稿"（评审 P6 #1）。
 * 主密钥缺失（NullBean）同样回 empty：与凭证 API 的 A0400 同语义、不同话术（运行时不该重试）。
 */
@Component
public class JdbcCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcCredentialProvider.class);

    private final CredentialRepository repository;
    private final WorkflowCatalog catalog;
    private final ObjectProvider<AesGcmCipher> cipherProvider;
    private final ObjectMapper mapper;

    public JdbcCredentialProvider(CredentialRepository repository, WorkflowCatalog catalog,
                                  ObjectProvider<AesGcmCipher> cipherProvider, ObjectMapper mapper) {
        this.repository = repository;
        this.catalog = catalog;
        this.cipherProvider = cipherProvider;
        this.mapper = mapper;
    }

    @Override
    public Optional<Map<String, String>> resolve(long credentialId, long workflowId) {
        AesGcmCipher cipher = cipherProvider.getIfAvailable();
        if (cipher == null) {
            log.warn("credentialId={} 解析失败：未配置主密钥", credentialId);
            return Optional.empty();
        }
        OptionalLong ownerId = catalog.ownerOf(workflowId);
        if (ownerId.isEmpty()) {
            log.warn("workflowId={} 不存在，credentialId={} 不予解析", workflowId, credentialId);
            return Optional.empty();
        }
        Optional<String> enc = repository.findSecretEncOwned(credentialId, ownerId.getAsLong());
        if (enc.isEmpty()) {
            // 不区分"不存在"与"不属于你的工作流"：区分本身就是探测他人凭证存在与否的侧信道
            log.warn("credentialId={} 对 workflowId={}（owner={}）不可用：不存在或不属于本流归属者",
                    credentialId, workflowId, ownerId.getAsLong());
            return Optional.empty();
        }
        try {
            String plain = cipher.decrypt(enc.get());
            if (!cipher.isPrimaryEnvelope(enc.get())) {
                // 轮换窗口：旧密钥/旧格式密文在首次使用时重封装为新主密钥，previous 才能最终退场
                try {
                    repository.rewrapOwned(credentialId, ownerId.getAsLong(), cipher.encrypt(plain));
                    log.info("credentialId={} 已在读取时重封装为新主密钥", credentialId);
                } catch (RuntimeException e) {
                    log.warn("credentialId={} 重封装失败（不影响本次解析）: {}", credentialId, e.getClass().getSimpleName());
                }
            }
            Map<String, String> secrets = mapper.readValue(plain, new TypeReference<Map<String, String>>() {
            });
            return Optional.of(secrets);
        } catch (Exception e) {
            // 只报类型：解密/解析链路任何异常都不得把内容带进日志
            log.warn("credentialId={} 解密/解析失败: {}", credentialId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}

package com.agentflow.server.persistence;

import com.agentflow.common.security.AesGcmCipher;
import com.agentflow.nodes.spi.CredentialProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * CredentialProvider 端口的服务端实现：密文读取 → AES-GCM 解密 → 键值对。
 * 失败一律回 Optional.empty()（日志只报 id 与异常类型，永不带出秘密内容）——节点侧映射为不可重试配置错。
 * 主密钥缺失（NullBean）同样回 empty：与凭证 API 的 A0400 同语义、不同话术（运行时不该重试）。
 */
@Component
public class JdbcCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcCredentialProvider.class);

    private final CredentialRepository repository;
    private final ObjectProvider<AesGcmCipher> cipherProvider;
    private final ObjectMapper mapper;

    public JdbcCredentialProvider(CredentialRepository repository,
                                  ObjectProvider<AesGcmCipher> cipherProvider, ObjectMapper mapper) {
        this.repository = repository;
        this.cipherProvider = cipherProvider;
        this.mapper = mapper;
    }

    @Override
    public Optional<Map<String, String>> resolve(long credentialId) {
        AesGcmCipher cipher = cipherProvider.getIfAvailable();
        if (cipher == null) {
            log.warn("credentialId={} 解析失败：未配置主密钥", credentialId);
            return Optional.empty();
        }
        Optional<String> enc = repository.findSecretEnc(credentialId);
        if (enc.isEmpty()) {
            log.warn("credentialId={} 不存在", credentialId);
            return Optional.empty();
        }
        try {
            Map<String, String> secrets = mapper.readValue(cipher.decrypt(enc.get()),
                    new TypeReference<Map<String, String>>() {
                    });
            return Optional.of(secrets);
        } catch (Exception e) {
            // 只报类型：解密/解析链路任何异常都不得把内容带进日志
            log.warn("credentialId={} 解密/解析失败: {}", credentialId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}

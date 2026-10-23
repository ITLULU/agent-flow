package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.security.AesGcmCipher;
import com.agentflow.server.persistence.JdbcCredentialProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * P7-3#16：轮换窗口内，轮换前落库的凭证（旧主密钥密文）仍可被业务解密，
 * 而轮换后的新写入一律用新主密钥；旧密钥单独一把解不开新密文。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:af-keyrotation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "agentflow.security.enabled=false",
        "agentflow.security.master-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=",
        "agentflow.security.master-key-previous=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "agentflow.nodes.llm.api-key=",
        "agentflow.nodes.image.api-key="})
class P7MasterKeyRotationE2ETests {

    /** 与上面 master-key-previous 对应：轮换前的旧主密钥。 */
    private static final byte[] OLD_KEY =
            AesGcmCipher.decodeKey("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

    /** 与上面 master-key 对应：轮换后的新主密钥。 */
    private static final byte[] NEW_KEY =
            AesGcmCipher.decodeKey("ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");

    @Autowired
    JdbcCredentialProvider provider;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AesGcmCipher cipher;

    @Test
    void credentialStoredBeforeRotationStillResolvesAfterRotation() {
        long workflowId = insertWorkflow(42L);
        String oldCipherText = new AesGcmCipher(OLD_KEY)
                .encrypt("{\"app_id\":\"wx-rot\",\"app_secret\":\"s3cret-before\"}");
        long credentialId = insertCredential(42L, oldCipherText);

        assertThat(provider.resolve(credentialId, workflowId))
                .contains(Map.of("app_id", "wx-rot", "app_secret", "s3cret-before"));

        // 首次读取即重封装：库里换成新主密钥密文，previous 退场后这条凭证依然可解
        String rewrapped = storedEnc(credentialId);
        assertThat(rewrapped).startsWith("v2:" + cipher.keyId() + ":");
        assertThat(new AesGcmCipher(NEW_KEY).decrypt(rewrapped))
                .contains("app_id", "wx-rot", "s3cret-before");
        assertThat(provider.resolve(credentialId, workflowId)).isPresent();
        assertThat(storedEnc(credentialId)).isEqualTo(rewrapped);

        String fresh = cipher.encrypt("{\"app_secret\":\"after\"}");
        assertThat(fresh).startsWith("v2:" + cipher.keyId() + ":");
        assertThatThrownBy(() -> new AesGcmCipher(OLD_KEY).decrypt(fresh))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("解密失败");
        assertThat(cipher.decrypt(fresh)).contains("after");
    }

    @Test
    void wrongCredentialOwnershipStillBlocksDecryptDuringRotation() {
        long workflowId = insertWorkflow(43L);
        long credentialId = insertCredential(44L, cipher.encrypt("{\"app_secret\":\"not-yours\"}"));
        assertThat(provider.resolve(credentialId, workflowId)).isEmpty();
    }

    private String storedEnc(long credentialId) {
        return jdbc.queryForObject("SELECT secret_json_enc FROM user_credentials WHERE id = ?",
                String.class, credentialId);
    }

    private long insertWorkflow(long ownerId) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO workflow (name, dsl, owner_id) VALUES ('rotation', '{\"nodes\":[]}', ?)",
                    new String[]{"id"});
            ps.setLong(1, ownerId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertCredential(long ownerId, String cipherText) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO user_credentials (owner_id, provider, name, secret_json_enc) "
                            + "VALUES (?, 'wechat', 'rot', ?)",
                    new String[]{"id"});
            ps.setLong(1, ownerId);
            ps.setString(2, cipherText);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}

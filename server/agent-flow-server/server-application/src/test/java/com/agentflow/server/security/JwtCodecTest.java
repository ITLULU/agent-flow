package com.agentflow.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * JwtCodec 安全红线测试：篡改/异算法/过期/错密钥一律拒绝——这些是"能不能上线"级别的断言。
 */
class JwtCodecTest {

    private static final byte[] SECRET = "unit-test-secret-at-least-32-chars!!".getBytes(StandardCharsets.UTF_8);

    private final JwtCodec codec = new JwtCodec(SECRET, new ObjectMapper(), Duration.ofHours(2));

    @Test
    void issueVerifyRoundTrip() {
        String token = codec.issue(42L, "alice", "user");
        Principal p = codec.verify(token);
        assertThat(p.uid()).isEqualTo(42L);
        assertThat(p.username()).isEqualTo("alice");
        assertThat(p.role()).isEqualTo("user");
    }

    @Test
    void tamperedPayloadRejected() {
        String token = codec.issue(42L, "alice", "user");
        String[] parts = token.split("\\.");
        // 把 uid 改成 0（冒充 admin）后重拼——签名不匹配必须拒
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forged = payload.replace("\"uid\":42", "\"uid\":0");
        String forgedToken = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forged.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThatThrownBy(() -> codec.verify(forgedToken))
                .isInstanceOf(ApiException.class).hasMessageContaining("签名");
    }

    @Test
    void algNoneRejected() {
        String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"uid\":0,\"sub\":\"hacker\",\"role\":\"admin\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.verify(noneHeader + "." + payload + "."))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> codec.verify(noneHeader + "." + payload + ".x"))
                .isInstanceOf(ApiException.class).hasMessageContaining("算法");
    }

    @Test
    void otherAlgHeaderRejected() {
        String hs512Header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"HS512\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String token = codec.issue(1L, "u", "user");
        String replaced = hs512Header + "." + token.split("\\.")[1] + "." + token.split("\\.")[2];
        assertThatThrownBy(() -> codec.verify(replaced))
                .isInstanceOf(ApiException.class).hasMessageContaining("算法");
    }

    @Test
    void expiredTokenRejected() {
        JwtCodec instantExpire = new JwtCodec(SECRET, new ObjectMapper(), Duration.ofSeconds(-10));
        String token = instantExpire.issue(1L, "alice", "user");
        assertThatThrownBy(() -> codec.verify(token)) // 用合法密钥验——过期就必须拒
                .isInstanceOf(ApiException.class).hasMessageContaining("过期");
    }

    @Test
    void wrongSecretRejected() {
        String token = codec.issue(1L, "alice", "user");
        JwtCodec other = new JwtCodec("another-secret-at-least-32-chars!!!x".getBytes(StandardCharsets.UTF_8),
                new ObjectMapper(), Duration.ofHours(2));
        assertThatThrownBy(() -> other.verify(token))
                .isInstanceOf(ApiException.class).hasMessageContaining("签名");
    }

    @Test
    void malformedTokensRejected() {
        for (String bad : new String[]{"", "a.b", "a.b.c.d", "not-a-jwt", null}) {
            assertThatThrownBy(() -> codec.verify(bad))
                    .as("token=%s", bad).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void shortSecretRefusedAtConstruction() {
        assertThatThrownBy(() -> new JwtCodec("too-short".getBytes(StandardCharsets.UTF_8),
                new ObjectMapper(), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

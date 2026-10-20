package com.agentflow.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 安全原语契约测试：格式自描述、恒时验证、错误输入不抛只 false；
 * AES-GCM 必须验证"篡改必失败"与"IV 不复用"（同明文两次密文不同）。
 */
class SecurityPrimitivesTest {

    // ---- PasswordHasher ----

    @Test
    void hashVerifyRoundTrip() {
        String stored = PasswordHasher.hash("S3cret-口令!");
        assertThat(stored).startsWith("pbkdf2$sha256$120000$");
        assertThat(PasswordHasher.verify("S3cret-口令!", stored)).isTrue();
    }

    @Test
    void wrongPasswordFails() {
        String stored = PasswordHasher.hash("right-password");
        assertThat(PasswordHasher.verify("wrong-password", stored)).isFalse();
        assertThat(PasswordHasher.verify("", stored)).isFalse();
        assertThat(PasswordHasher.verify(null, stored)).isFalse();
    }

    @Test
    void samePasswordProducesDifferentHashesPerSalt() {
        String a = PasswordHasher.hash("same");
        String b = PasswordHasher.hash("same");
        assertThat(a).isNotEqualTo(b); // 随机盐：撞库拿不到可比较的散列
        assertThat(PasswordHasher.verify("same", a)).isTrue();
        assertThat(PasswordHasher.verify("same", b)).isTrue();
    }

    @Test
    void malformedStoredNeverThrowsNeverPasses() {
        for (String bad : new String[]{"", "plaintext", "pbkdf2$sha256$abc$x$y", "pbkdf2$md5$1$AA$AA", "bcrypt$1$2$3"}) {
            assertThat(PasswordHasher.verify("whatever", bad)).as("stored=%s", bad).isFalse();
        }
    }

    @Test
    void tamperedHashSegmentFails() {
        String stored = PasswordHasher.hash("original");
        String[] parts = stored.split("\\$");
        byte[] digest = Base64.getDecoder().decode(parts[4]);
        digest[0] ^= 0x01; // 翻转 1 bit
        String tampered = parts[0] + "$" + parts[1] + "$" + parts[2] + "$" + parts[3] + "$"
                + Base64.getEncoder().encodeToString(digest);
        assertThat(PasswordHasher.verify("original", tampered)).isFalse();
    }

    // ---- AesGcmCipher ----

    private static AesGcmCipher cipher(byte seed) {
        byte[] key = new byte[32];
        key[0] = seed;
        key[31] = (byte) (seed + 1);
        return new AesGcmCipher(key);
    }

    @Test
    void encryptDecryptRoundTripUtf8() {
        AesGcmCipher c = cipher((byte) 7);
        String secret = "{\"app_id\":\"wx123\",\"app_secret\":\"秘密‑s3cret\"}";
        String enc = c.encrypt(secret);
        assertThat(enc).isNotEqualTo(secret).doesNotContain("wx123");
        assertThat(c.decrypt(enc)).isEqualTo(secret);
    }

    @Test
    void ivNeverReused_samePlainDifferentCipherText() {
        AesGcmCipher c = cipher((byte) 1);
        assertThat(c.encrypt("same-plain")).isNotEqualTo(c.encrypt("same-plain"));
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String enc = cipher((byte) 2).encrypt("topsecret");
        assertThatThrownBy(() -> cipher((byte) 3).decrypt(enc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void tamperedCipherTextRejected() {
        AesGcmCipher c = cipher((byte) 4);
        byte[] raw = Base64.getDecoder().decode(c.encrypt("topsecret"));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> c.decrypt(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masterKeyMustBe32Bytes() {
        assertThatThrownBy(() -> new AesGcmCipher(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmCipher(null))
                .isInstanceOf(IllegalArgumentException.class);
        AesGcmCipher.decodeKey(Base64.getEncoder().encodeToString(new byte[32])); // 合法 base64 不抛
    }
}

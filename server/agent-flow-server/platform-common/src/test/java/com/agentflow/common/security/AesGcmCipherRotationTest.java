package com.agentflow.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** P7-3#16：主密钥轮换——旧密文双读、新密文单写、kid 可核对、旧密钥退场后新密文仍可解。 */
class AesGcmCipherRotationTest {

    private static byte[] key(byte seed) {
        byte[] key = new byte[32];
        key[0] = seed;
        key[31] = (byte) (seed + 1);
        return key;
    }

    @Test
    void oldCipherTextReadableDuringRotationWindow() {
        AesGcmCipher oldCipher = new AesGcmCipher(key((byte) 1));
        String cipherText = oldCipher.encrypt("{\"app_secret\":\"secret-a\"}");

        AesGcmCipher rotated = new AesGcmCipher(key((byte) 2), key((byte) 1));
        assertThat(rotated.decrypt(cipherText)).isEqualTo("{\"app_secret\":\"secret-a\"}");
        // P3 起的老封装（裸 Base64）同样可读：v2 的载荷就是老格式
        String legacy = cipherText.substring(cipherText.lastIndexOf(':') + 1);
        assertThat(rotated.decrypt(legacy)).isEqualTo("{\"app_secret\":\"secret-a\"}");
    }

    @Test
    void newEncryptionUsesPrimaryOnly() {
        AesGcmCipher oldCipher = new AesGcmCipher(key((byte) 1));
        AesGcmCipher rotated = new AesGcmCipher(key((byte) 2), key((byte) 1));

        String cipherText = rotated.encrypt("new-secret");
        assertThat(cipherText).startsWith("v2:" + rotated.keyId() + ":");
        assertThat(cipherText).doesNotContain("new-secret");
        assertThat(cipherText).isNotEqualTo(rotated.encrypt("new-secret"));

        // 旧密钥不能解新密文：删除 previous 之前必须先完成重加密
        AesGcmCipher previousOnly = new AesGcmCipher(key((byte) 1));
        assertThatThrownBy(() -> previousOnly.decrypt(cipherText))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void reEncryptedValueSurvivesPreviousKeyRemoval() {
        String plain = "{\"token\":\"abc\"}";
        String oldCipherText = new AesGcmCipher(key((byte) 1)).encrypt(plain);
        AesGcmCipher rotated = new AesGcmCipher(key((byte) 2), key((byte) 1));
        String reEncrypted = rotated.encrypt(rotated.decrypt(oldCipherText));

        AesGcmCipher primaryOnly = new AesGcmCipher(key((byte) 2));
        assertThat(primaryOnly.decrypt(reEncrypted)).isEqualTo(plain);
    }

    @Test
    void unknownOrMalformedKidRejected() {
        AesGcmCipher rotated = new AesGcmCipher(key((byte) 2), key((byte) 1));
        String good = new AesGcmCipher(key((byte) 1)).encrypt("x");
        String payload = good.substring(good.lastIndexOf(':') + 1);

        assertThatThrownBy(() -> rotated.decrypt("v2:deadbeefdeadbeef:" + payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("解密失败");
        assertThatThrownBy(() -> rotated.decrypt("v2:no-payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式非法");
        assertThatThrownBy(() -> rotated.decrypt("v2:" + rotated.keyId() + ":not-base64!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式非法");
    }

    @Test
    void bothKeysWrongStillFails() {
        String cipherText = new AesGcmCipher(key((byte) 1)).encrypt("x");
        AesGcmCipher wrong = new AesGcmCipher(key((byte) 5), key((byte) 6));
        assertThatThrownBy(() -> wrong.decrypt(cipherText))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void keyIdsAreStableAndDistinct() {
        AesGcmCipher a = new AesGcmCipher(key((byte) 1));
        AesGcmCipher b = new AesGcmCipher(key((byte) 2), key((byte) 1));
        assertThat(a.keyId()).hasSize(16).isEqualTo(new AesGcmCipher(key((byte) 1)).keyId());
        assertThat(a.keyId()).isNotEqualTo(b.keyId());
    }

    @Test
    void previousKeyMustBe32Bytes() {
        assertThatThrownBy(() -> new AesGcmCipher(key((byte) 1), new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上一把主密钥");
    }
}

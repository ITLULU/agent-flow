package com.agentflow.common.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭证加密原语：AES-256-GCM（认证加密，篡改密文/错密钥必然解密失败，不带裸 CBC 的填充 oracle 风险）。
 * 封装格式：Base64( 12B 随机IV || ciphertext||tag )——每条记录独立随机 IV，禁止复用。
 * 主密钥来自环境变量 AGENTFLOW_MASTER_KEY（Base64 的 32 字节），严禁入库/入日志。
 */
public final class AesGcmCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /** @param masterKey32Bytes 必须恰为 32 字节，否则启动即失败（配置错误不该拖到运行期） */
    public AesGcmCipher(byte[] masterKey32Bytes) {
        if (masterKey32Bytes == null || masterKey32Bytes.length != 32) {
            throw new IllegalArgumentException("主密钥必须为 32 字节（base64 解码后的 AGENTFLOW_MASTER_KEY）");
        }
        this.key = new SecretKeySpec(masterKey32Bytes, "AES");
    }

    public static byte[] decodeKey(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM 加密失败（JDK 环境异常）", e);
        }
    }

    /** @throws IllegalArgumentException 密文被篡改/密钥不对/格式非法——调用方映射为协议错误，绝不返回部分明文 */
    public String decrypt(String envelope) {
        try {
            byte[] raw = Base64.getDecoder().decode(envelope);
            if (raw.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文封装长度非法");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("凭证解密失败：密文篡改或主密钥不匹配", e);
        }
    }
}

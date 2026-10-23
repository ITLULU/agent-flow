package com.agentflow.common.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭证加密原语：AES-256-GCM（认证加密，篡改密文/错密钥必然解密失败，不带裸 CBC 的填充 oracle 风险）。
 * 新密文封装：v2:{kid}:Base64( 12B 随机IV || ciphertext||tag )——kid 为主密钥指纹，轮换期据此区分新旧行；
 * 兼容读取 P3 起的旧格式 Base64(IV||ct)（无 kid，按"主密钥→上一把密钥"顺序试解）。
 * 双读单写：新加密永远用主密钥，旧密钥只参与解密；主密钥来自 AGENTFLOW_MASTER_KEY（Base64 32B），
 * 上一把来自 AGENTFLOW_MASTER_KEY_PREVIOUS（可选），两者都严禁入库/入日志。
 */
public final class AesGcmCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String ENVELOPE_PREFIX = "v2:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec primary;
    private final String primaryKid;
    private final SecretKeySpec previous;   // null = 未处于轮换窗口
    private final String previousKid;

    /** @param masterKey32Bytes 必须恰为 32 字节，否则启动即失败（配置错误不该拖到运行期） */
    public AesGcmCipher(byte[] masterKey32Bytes) {
        this(masterKey32Bytes, null);
    }

    /** @param previousKey32Bytes 轮换期旧密钥，可 null；只解密不加密 */
    public AesGcmCipher(byte[] masterKey32Bytes, byte[] previousKey32Bytes) {
        this.primary = keyOf(masterKey32Bytes, "主密钥");
        this.primaryKid = keyIdOf(masterKey32Bytes);
        this.previous = previousKey32Bytes == null ? null : keyOf(previousKey32Bytes, "上一把主密钥");
        this.previousKid = previousKey32Bytes == null ? null : keyIdOf(previousKey32Bytes);
    }

    public static byte[] decodeKey(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    /** 主密钥指纹（SHA-256 前 8 字节）：用于核对生效密钥与排查未迁移密文，不可逆推密钥。 */
    public String keyId() {
        return primaryKid;
    }

    /** 密文是否已由当前主密钥封装：false 表示旧密钥/旧格式，读取方可在轮换窗口内顺手重封装。 */
    public boolean isPrimaryEnvelope(String envelope) {
        return envelope != null && envelope.startsWith(ENVELOPE_PREFIX + primaryKid + ":");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, primary, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            return ENVELOPE_PREFIX + primaryKid + ":" + Base64.getEncoder().encodeToString(buf.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM 加密失败（JDK 环境异常）", e);
        }
    }

    /** @throws IllegalArgumentException 密文被篡改/密钥不对/格式非法——调用方映射为协议错误，绝不返回部分明文 */
    public String decrypt(String envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("凭证解密失败：密文为空");
        }
        if (envelope.startsWith(ENVELOPE_PREFIX)) {
            return decryptV2(envelope);
        }
        byte[] raw = decodeLegacy(envelope);
        String plain = tryDecrypt(raw, primary);
        if (plain == null && previous != null) {
            plain = tryDecrypt(raw, previous);
        }
        if (plain == null) {
            throw new IllegalArgumentException("凭证解密失败：密文篡改或主密钥不匹配");
        }
        return plain;
    }

    private String decryptV2(String envelope) {
        int kidEnd = envelope.indexOf(':', ENVELOPE_PREFIX.length());
        if (kidEnd < 0) {
            throw new IllegalArgumentException("凭证解密失败：密文封装格式非法");
        }
        String kid = envelope.substring(ENVELOPE_PREFIX.length(), kidEnd);
        byte[] raw = decodeLegacy(envelope.substring(kidEnd + 1));
        SecretKeySpec key = null;
        if (kid.equals(primaryKid)) {
            key = primary;
        } else if (previousKid != null && kid.equals(previousKid)) {
            key = previous;
        }
        String plain = key == null ? null : tryDecrypt(raw, key);
        if (plain == null) {
            throw new IllegalArgumentException("凭证解密失败：密文篡改或主密钥不匹配");
        }
        return plain;
    }

    private static byte[] decodeLegacy(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            if (raw.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文封装长度非法");
            }
            return raw;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("凭证解密失败：密文封装格式非法", e);
        }
    }

    /** 解密成功返回明文；认证失败返回 null（由调用方决定是否换下一把密钥），不把异常细节带出去。 */
    private static String tryDecrypt(byte[] raw, SecretKeySpec key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static SecretKeySpec keyOf(byte[] key, String label) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException(label + "必须为 32 字节（base64 解码后的 AGENTFLOW_MASTER_KEY）");
        }
        return new SecretKeySpec(key, "AES");
    }

    private static String keyIdOf(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }
}

package com.agentflow.server.security;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * JWT（HS256）签发/校验——纯 JDK Mac + Jackson，零新依赖（jjwt/nimbus 不在本地缓存，下载被禁）。
 * 安全红线（单测钉死）：验签必须拒 alg=none、异算法、签名/载荷篡改；比较走 MessageDigest.isEqual 恒时路径。
 * 仅支持 HS256 一种算法即是最小攻击面：算法字段不做查表分发，杜绝 alg 混淆类攻击。
 */
public final class JwtCodec {

    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public JwtCodec(byte[] secret, ObjectMapper mapper, Duration ttl) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("JWT 密钥不足 32 字节（AGENTFLOW_JWT_SECRET 需为足够长的随机串）");
        }
        this.secret = secret;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    public String issue(long uid, String username, String role) {
        long now = Instant.now().getEpochSecond();
        long exp = now + ttl.getSeconds();
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(
                    java.util.Map.of("uid", uid, "sub", username, "role", role, "iat", now, "exp", exp));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 载荷序列化失败", e);
        }
        String signingInput = B64URL.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8))
                + "." + B64URL.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + B64URL.encodeToString(sign(signingInput));
    }

    /** @throws ApiException(UNAUTHORIZED) 任何校验失败——原因只进日志，响应统一 A0401 不泄露细节 */
    public Principal verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "JWT 结构非法");
        }
        try {
            JsonNode header = mapper.readTree(B64URL_DEC.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "JWT 算法不被接受"); // 含 alg=none 场景
            }
            byte[] expectedSig = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expectedSig, B64URL_DEC.decode(parts[2]))) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "JWT 签名不匹配");
            }
            JsonNode payload = mapper.readTree(B64URL_DEC.decode(parts[1]));
            long exp = payload.path("exp").asLong(0);
            if (exp < Instant.now().getEpochSecond()) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "JWT 已过期");
            }
            return new Principal(payload.path("uid").asLong(),
                    payload.path("sub").asText(), payload.path("role").asText("user"));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "JWT 解析失败: " + e.getClass().getSimpleName());
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 不可用（JDK 环境异常）", e);
        }
    }
}

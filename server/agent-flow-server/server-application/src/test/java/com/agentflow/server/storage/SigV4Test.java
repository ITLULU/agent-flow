package com.agentflow.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SigV4 口径单测：只钉"能被外部验证的不变量"。
 *
 * 完整签名的正确性不由这里自证（自己算一遍再比自己是循环论证），这里管两件外部已知答案的事：
 * sha256 与 HMAC 的原语实现、以及编码/排序/头部结构这些最容易在重构时漂移的规则。
 * "S3 是否接受这个签名"由真端点验证（MinIO 实测 + 桩 E2E），签错就是 403，不会静默过。
 */
class SigV4Test {

    private static final Instant T = Instant.parse("2026-10-23T05:00:00Z");

    @Test
    void sha256MatchesPublishedVectors() {
        // 空串与 "abc" 是公开标准向量（RFC 系列与常见实现文档），不是本仓库自定义口径
        assertThat(SigV4.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(SigV4.sha256Hex("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    /** 已知答案向量：值取自独立实现（`printf 'what do ya want for nothing?' | openssl dgst -sha256 -hmac Jefe`），不是本仓库自算自比。 */
    @Test
    void hmacMatchesRfc4231TestCase2() {
        byte[] key = "Jefe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String data = "what do ya want for nothing?";
        assertThat(hexOf(SigV4.hmac(key, data)))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void canonicalUriKeepsSlashesAndEncodesOnce() {
        assertThat(SigV4.canonicalUri("/b/a.md")).isEqualTo("/b/a.md");
        assertThat(SigV4.canonicalUri("/b/2026 秋 cover.png")).isEqualTo("/b/2026%20%E7%A7%8B%20cover.png");
        assertThat(SigV4.canonicalUri("/b/tilde~and*star")).isEqualTo("/b/tilde~and%2Astar");
        assertThat(SigV4.canonicalUri("")).isEqualTo("/");
        assertThat(SigV4.canonicalUri("/b/")).isEqualTo("/b/");
        // 已含 % 的原始键必须编成 %25，且编码只发生一次（重复编码是这类实现最典型的静默 403）
        assertThat(SigV4.canonicalUri("/b/100%-off")).isEqualTo("/b/100%25-off");
        assertThat(SigV4.canonicalUri(SigV4.canonicalUri("/b/a b.md"))).contains("%2520");
    }

    @Test
    void canonicalQuerySortsAndEncodesBothSides() {
        assertThat(SigV4.canonicalQuery("")).isEmpty();
        assertThat(SigV4.canonicalQuery("b=2&a=1")).isEqualTo("a=1&b=2");
        assertThat(SigV4.canonicalQuery("prefix=a/b&max-keys=5")).isEqualTo("max-keys=5&prefix=a%2Fb");
        assertThat(SigV4.canonicalQuery("marker")).isEqualTo("marker=");
    }

    @Test
    void authorizationHeaderHasExactShapeAndIsPayloadSensitive() {
        Map<String, String> a = SigV4.sign("PUT", "127.0.0.1:9000", "/b/a.md", "",
                Map.of("content-type", "application/octet-stream"), "body".getBytes(), "AKID", "secret",
                "us-east-1", T);
        String auth = a.get("Authorization");
        assertThat(auth).matches("^AWS4-HMAC-SHA256 Credential=AKID/20261023/us-east-1/s3/aws4_request, "
                + "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, Signature=[0-9a-f]{64}$");
        assertThat(a.get("X-Amz-Date")).isEqualTo("20261023T050000Z");
        assertThat(a.get("X-Amz-Content-Sha256"))
                .isEqualTo(SigV4.sha256Hex("body".getBytes()));

        // 签名必须对内容、密钥、时间敏感：否则"改了字节但签名照用"这种错误会被远端放行
        Map<String, String> otherBody = SigV4.sign("PUT", "127.0.0.1:9000", "/b/a.md", "",
                Map.of("content-type", "application/octet-stream"), "bodx".getBytes(), "AKID", "secret",
                "us-east-1", T);
        assertThat(otherBody.get("Authorization")).isNotEqualTo(auth);
        assertThat(SigV4.sign("PUT", "127.0.0.1:9000", "/b/a.md", "", Map.of(), "body".getBytes(),
                "AKID", "secret", "us-east-1", T.plusSeconds(1)).get("Authorization")).isNotEqualTo(auth);
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

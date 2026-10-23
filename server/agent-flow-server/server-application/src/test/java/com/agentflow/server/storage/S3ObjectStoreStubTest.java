package com.agentflow.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.server.config.ObjectStorageProperties;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S3 客户端的线上行为验证（对本地桩，CI 无需 MinIO）：
 * 编码只做一次、Host 与签名口径一致、404 按语义而非故障、对端报文里的凭证不外泄。
 * "签名本身对不对"不在这里自证——由 MinIO 真端点测试与 E2E 负责（签错就是 403）。
 */
class S3ObjectStoreStubTest {

    private static final String AK = "AKTEST1234";
    private static final String SK = "secret-should-never-leak";

    private StubS3 stub;
    private S3ObjectStore store;

    @BeforeEach
    void start() throws Exception {
        stub = new StubS3();
        store = new S3ObjectStore(props(true));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private ObjectStorageProperties props(boolean enabled) {
        return new ObjectStorageProperties(enabled, stub.base(), "af-stub", "us-east-1", AK, SK,
                "artifacts", 2000, 5000);
    }

    @Test
    void putGetDeleteRoundTrip() {
        byte[] body = "正文一".getBytes(StandardCharsets.UTF_8);
        store.put("7/article.md", body, "application/octet-stream");
        assertThat(store.exists("7/article.md")).isTrue();
        assertThat(new String(store.get("7/article.md"), StandardCharsets.UTF_8)).isEqualTo("正文一");

        store.delete("7/article.md");
        assertThat(store.exists("7/article.md")).isFalse();
        assertThat(store.get("7/article.md")).isNull();
        // 再删一次是幂等的：404 不该被当故障抛
        store.delete("7/article.md");
    }

    @Test
    void pathIsEncodedExactlyOnceOnTheWire() {
        String key = "7/assets/秋 封面.png";
        store.put(key, new byte[] {1}, "application/octet-stream");

        String expected = SigV4.canonicalUri("/af-stub/" + key);
        assertThat(expected).contains("%20").doesNotContain("%2520");
        assertThat(stub.log).contains("PUT " + expected);
    }

    @Test
    void hostHeaderIsSignedWithTheExactAuthority() {
        store.put("7/meta.json", new byte[] {2}, "application/octet-stream");
        String authority = stub.base().substring("http://".length());
        Map<String, String> sent = stub.headerLog.get(stub.headerLog.size() - 1);

        // Host 与签名里的 host 必须一模一样：JDK 客户端自己补 Host，任何大小写/端口差异都会变成远端 403
        assertThat(sent.get("host")).isEqualTo(authority);
        assertThat(sent.get("authorization"))
                .contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date")
                .contains("Credential=" + AK + "/");
        assertThat(sent.get("x-amz-content-sha256")).isEqualTo(SigV4.sha256Hex(new byte[] {2}));
    }

    /** 反向验证：写失败必须翻成依赖不可用，且不能把 AKID/密钥或对端报文里的凭证片段带进错误信息。 */
    @Test
    void writeFailureIsReportedWithoutLeakingCredentials() {
        stub.failWrites = true;
        assertThatThrownBy(() -> store.put("7/article.md", new byte[] {3}, "application/octet-stream"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("500").contains("InternalError");
                    assertThat(e.getMessage()).doesNotContain(SK).doesNotContain(AK)
                            .doesNotContain("AKIA-LEAK-TEST");
                });
    }

    @Test
    void ensureBucketCreatesWhenAbsentAndSkipsWhenPresent() {
        assertThat(stub.bucketCreated()).isFalse();
        store.ensureBucket();
        assertThat(stub.bucketCreated()).isTrue();

        // 桶已存在（HEAD 通）⇒ 不再重复建桶：重复建桶在真端点是 409，报成故障会让每次启动都红
        stub.log.clear();
        stub.objects.put("", new byte[0]);
        store.ensureBucket();
        assertThat(stub.log).doesNotContain("PUTBUCKET");
    }

    /** 配置面 fail-fast：开了开关却缺件 ⇒ 启动期就炸，不留"看起来开了其实一直在失败"的静默态。 */
    @Test
    void enabledWithoutRequiredFieldsFailsFast() {
        assertThatThrownBy(() -> new ObjectStorageProperties(true, stub.base(), "af-stub", null, AK, null,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("配置不全");

        assertThatThrownBy(() -> new ObjectStorageProperties(true, "127.0.0.1:9000", "af-stub", "us-east-1",
                AK, SK, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");

        ObjectStorageProperties off = new ObjectStorageProperties(false, null, null, null, null, null, null,
                null, null);
        assertThat(off.enabled()).isFalse();
        assertThat(off.region()).isEqualTo("us-east-1");
        assertThat(off.keyPrefix()).isEqualTo("artifacts");
    }
}

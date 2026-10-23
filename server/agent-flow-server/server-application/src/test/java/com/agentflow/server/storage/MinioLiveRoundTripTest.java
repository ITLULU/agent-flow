package com.agentflow.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.server.config.ObjectStorageProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 真 MinIO 往返（P7-3#12b 的实机验证面）。默认跳过：只有显式给出 AGENTFLOW_STORAGE_ENDPOINT + 凭证时才跑，
 * CI 上没有对象存储也不该红。
 *
 * 为什么值得单独跑一次：桩服务器不校验签名，它只能证明"我们发了个像样的 Authorization 头"；
 * SigV4 是否真的算对，只有真 S3 服务说了才算——而 SigV4 的 canonical URI 编码错一位，
 * 表现是线上永远 403，本地全绿。这条测试就是用来把这类"桩会原谅、真服务不会"的错揪出来。
 *
 * 只用一个 scratch 桶（af-live-test-*），用例结束删光自己写的对象；凭证一律来自环境变量，不落盘不回显。
 */
class MinioLiveRoundTripTest {

    private static final String ENDPOINT = System.getenv("AGENTFLOW_STORAGE_ENDPOINT");
    private static final String ACCESS_KEY = System.getenv("AGENTFLOW_STORAGE_ACCESS_KEY");
    private static final String SECRET_KEY = System.getenv("AGENTFLOW_STORAGE_SECRET_KEY");

    private final List<String> written = new ArrayList<>();
    private S3ObjectStore store;
    private String bucket;

    @BeforeEach
    void connect() {
        assumeTrue(ENDPOINT != null && !ENDPOINT.isBlank(), "未配置 AGENTFLOW_STORAGE_ENDPOINT，跳过真对象存储验证");
        assumeTrue(ACCESS_KEY != null && SECRET_KEY != null, "未配置对象存储凭证，跳过");
        bucket = "af-live-test-" + UUID.randomUUID().toString().substring(0, 8);
        store = new S3ObjectStore(new ObjectStorageProperties(true, ENDPOINT, bucket, "us-east-1",
                ACCESS_KEY, SECRET_KEY, "artifacts", 5000, 30000));
        store.ensureBucket();
    }

    @AfterEach
    void cleanup() {
        if (store == null) {
            return;
        }
        // 桶本身留给 MinIO 生命周期策略处理（ObjectStore 端口只有对象级删除，不为了测试扩面）
        written.forEach(key -> {
            try {
                store.delete(key);
            } catch (RuntimeException ignored) {
                // 清不掉就留在 scratch 桶里，不影响断言结论
            }
        });
    }

    @Test
    void roundTripsUnicodeKeyAgainstRealServer() {
        String key = "artifacts/9/assets/秋 封面-3x4.png";
        byte[] body = "真机往返".getBytes(StandardCharsets.UTF_8);
        written.add(key);

        store.put(key, body, "application/octet-stream");
        assertThat(store.exists(key)).isTrue();
        assertThat(new String(store.get(key), StandardCharsets.UTF_8)).isEqualTo("真机往返");

        assertThat(store.exists("artifacts/9/assets/没有这个对象.png")).isFalse();
        assertThat(store.get("artifacts/9/assets/没有这个对象.png")).isNull();

        store.delete(key);
        assertThat(store.exists(key)).isFalse();
        store.delete(key); // 幂等：删不存在的对象不该炸
    }

    /** 反向验证：错密钥必须被真服务拒绝。桩服务器不会告诉你签名算错了，只有真服务会。 */
    @Test
    void wrongSecretIsRejectedByRealServerWithoutLeakingIt() {
        assumeTrue(store != null, "跳过态下没有连接");
        S3ObjectStore bad = new S3ObjectStore(new ObjectStorageProperties(true, ENDPOINT, bucket, "us-east-1",
                ACCESS_KEY, "wrong-secret-" + UUID.randomUUID(), "artifacts", 5000, 30000));
        String key = "artifacts/9/article.md";
        assertThatThrownBy(() -> bad.put(key, "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("对象存储返回 403");
                    assertThat(e.getMessage()).doesNotContain("wrong-secret").doesNotContain(ACCESS_KEY);
                });
        assertThat(store.exists(key)).isFalse(); // 被拒的写没有偷偷落盘
    }
}

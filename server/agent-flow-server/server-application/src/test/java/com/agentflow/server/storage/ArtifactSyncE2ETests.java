package com.agentflow.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.nodes.llm.LlmGateway;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P7-3#12b 成品 → 对象存储同步 e2e（对 S3 兼容桩真跑，CI 无凭证也必须绿）。
 *
 * #12a 证的是"本地丢了能补回来"，这里证的是另一件事：**换实例/重建卷之后，远端那一份真的能用**。
 * 所以每条都要制造一次失败，而不是只看 happy path 的绿灯：
 * 1. 同步成功要把磁盘上的每个文件（含图片）都传上去，并让读回落点真的从远端出字节；
 * 2. PUT 被拒 / PUT 成功但对象不在 / 远端对象被改坏——三种"看起来同步好了"都要翻不出绿灯；
 * 3. 同步失败只动 sync_*，绝不动 file_state：备份面坏了却说"稿子没了"是最坏的误导；
 * 4. 半套成品（file_state=incomplete）拒绝上远端，免得把"本地丢了还能补"变成"远端也错了"。
 *
 * 后台扫描器被显式推迟到一小时后触发：失败注入必须只由测试自己 sweep，否则后台线程会抢先治愈，
 * 断言就变成撞运气。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-artifact-sync;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=sync-secret-at-least-32-chars!!!",
                "agentflow.security.rate-limit-per-minute=0",
                "agentflow.nodes.image.api-key=sk-e2e"})
class ArtifactSyncE2ETests {

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String BUCKET = "af-e2e";
    private static final String SECRET = "e2e-secret-key-must-not-leak";

    private static HttpServer upstream;
    private static String upstreamBase;
    private static StubS3 s3;

    @TempDir
    static Path library;

    @DynamicPropertySource
    static void stubEndpoints(DynamicPropertyRegistry registry) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();
        upstream.createContext("/hot", ex -> {
            byte[] bytes = "[{\"title\":\"秋天适合读的5本书\",\"hot\":\"420万\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (var os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        upstream.createContext("/api/v1/services/aigc/text2image/image-synthesis", ex -> {
            ex.getRequestBody().readAllBytes();
            json(ex, 200, "{\"output\":{\"task_id\":\"sync-task-1\"}}");
        });
        upstream.createContext("/api/v1/tasks/sync-task-1", ex -> json(ex, 200,
                "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"url\":\""
                        + upstreamBase + "/img/cover.png\"}]}}"));
        upstream.createContext("/img/cover.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, PNG.length);
            try (var os = ex.getResponseBody()) {
                os.write(PNG);
            }
        });
        upstream.start();

        s3 = new StubS3();
        registry.add("agentflow.nodes.image.base-url", () -> upstreamBase);
        registry.add("agentflow.content.library-dir", () -> library.toString());
        registry.add("agentflow.content.storage.enabled", () -> "true");
        registry.add("agentflow.content.storage.endpoint", s3::base);
        registry.add("agentflow.content.storage.bucket", () -> BUCKET);
        registry.add("agentflow.content.storage.access-key", () -> "e2e-ak");
        registry.add("agentflow.content.storage.secret-key", () -> SECRET);
        registry.add("agentflow.content.storage.sweep-initial-delay-ms", () -> "3600000");
    }

    @AfterAll
    static void stopStubs() {
        if (upstream != null) {
            upstream.stop(0);
        }
        if (s3 != null) {
            s3.close();
        }
    }

    private static void json(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @TestConfiguration
    static class FakeLlmConfig {

        @Bean
        @Primary
        LlmGateway fakeLlmGateway() {
            return request -> new LlmGateway.Reply(
                    "【标题】秋天，把书一本本读完\n"
                            + "【摘要】一份能直接照着读的秋日书单\n"
                            + "【标签】读书分享｜秋日书单｜自我成长\n"
                            + "【金句】读完一本，才算真的过完一个秋天\n"
                            + "【封面文案】秋日读书清单｜5本\n"
                            + "【正文】秋天翻书的声音比夏天好听。\n第一本适合在窗前读。",
                    "fake-qwen", 123);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ArtifactSyncService syncService;

    @BeforeEach
    void resetStubs() throws Exception {
        try (var dirs = Files.list(library)) {
            for (Path p : dirs.toList()) {
                if (Files.isDirectory(p)) {
                    deleteTree(p);
                }
            }
        }
        s3.objects.clear();
        s3.log.clear();
        s3.headerLog.clear();
        s3.failWrites = false;
        s3.reportMissing = false;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private ResponseEntity<String> post(String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<byte[]> getBytes(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private static String extract(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        int start = json.indexOf(':', i) + 1;
        if (json.charAt(start) == '"') {
            return json.substring(start + 1, json.indexOf('"', start + 1));
        }
        int end = start;
        while (Character.isDigit(json.charAt(end))) {
            end++;
        }
        return json.substring(start, end);
    }

    private String login(String username) {
        post("/api/auth/register", "{\"username\":\"" + username + "\",\"password\":\"passw0rd-123\"}", null);
        return extract(post("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"passw0rd-123\"}", null).getBody(), "token");
    }

    private static String key(long artifactId, String rel) {
        return "artifacts/" + artifactId + "/" + rel;
    }

    private long runPipeline(String token) throws Exception {
        String dsl = """
                {"name":"秋日读书同步闭环","nodes":[
                  {"id":"h","type":"hotspot.fetch","params":{"sources":"stub","limit":5,\
                "endpointOverride":"{\\"stub\\":\\"%s/hot\\"}"}},
                  {"id":"c","type":"copy.write","params":{"mode":"xiaohongshu","length":300}},
                  {"id":"i","type":"image.cover","params":{"kind":"cover","ratio":"3:4",\
                "pollIntervalMs":50,"maxWaitMs":5000}},
                  {"id":"p","type":"publish.local","params":{}}
                ],"edges":[{"from":"h","to":"c"},{"from":"c","to":"i"},{"from":"i","to":"p"}]}
                """.formatted(upstreamBase);
        long workflowId = Long.parseLong(extract(post("/api/workflows", dsl, token).getBody(), "workflowId"));
        long executionId = Long.parseLong(extract(
                post("/api/workflows/" + workflowId + "/executions", "{}", token).getBody(), "executionId"));
        String detail = null;
        for (int i = 0; i < 100; i++) {
            detail = get("/api/executions/" + executionId, token).getBody();
            String status = extract(detail, "status");
            if (!"RUNNING".equals(status) && !"QUEUED".equals(status)) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        assertThat(extract(detail, "status")).isEqualTo("SUCCESS");
        String list = get("/api/artifacts?kind=note", token).getBody();
        return Long.parseLong(extract(list, "id"));
    }

    @Test
    void syncUploadsEveryFileAndRemoteServesAfterLocalVolumeLost() throws Exception {
        String token = login("syncAuthor");
        long artifactId = runPipeline(token);
        Path dir = library.resolve(String.valueOf(artifactId));
        byte[] mdBefore = Files.readAllBytes(dir.resolve("article.md"));
        byte[] pngBefore = Files.readAllBytes(dir.resolve("assets/cover-1-3x4.png"));

        // 同步前：待同步清单要真的看得见这条（enabled=true 而不是"接口没实现"）
        String pending = get("/api/artifacts/sync/pending", token).getBody();
        assertThat(pending).contains("\"enabled\":true").contains("\"id\":" + artifactId);

        ResponseEntity<String> synced = post("/api/artifacts/" + artifactId + "/sync", "{}", token);
        assertThat(synced.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(synced.getBody()).contains("\"state\":\"synced\"").contains("\"files\":3");

        // 远端落的是三个文件（含图片），不是"只传了正文"
        assertThat(s3.objects.keySet()).containsExactlyInAnyOrder(
                key(artifactId, "article.md"), key(artifactId, "meta.json"), key(artifactId, "assets/cover-1-3x4.png"));
        assertThat(s3.objects.get(key(artifactId, "assets/cover-1-3x4.png"))).isEqualTo(pngBefore);
        assertThat(s3.bucketCreated()).isTrue(); // 启动时的建桶探针真跑过（log 每条用例会清空，所以看持久标记）

        // 模拟换实例/重建卷：整个成品目录消失
        deleteTree(dir);
        assertThat(Files.exists(dir)).isFalse();
        assertThat(get("/api/artifacts/" + artifactId, token).getBody())
                .contains("\"file_state\":\"complete\"").contains("\"sync_state\":\"synced\"");

        // 读回落点：本地没了，字节必须从远端出来且与原件一致（文本与图片都验）
        // 路径按前端实际用法原样传（query 里的 "/" 不需要转义；%2F 会被 RestTemplate 二次编码成假 404）
        byte[] served = getBytes("/api/artifacts/" + artifactId + "/file?path=article.md", token).getBody();
        assertThat(served).isEqualTo(mdBefore);
        byte[] servedPng = getBytes("/api/artifacts/" + artifactId
                + "/file?path=assets/cover-1-3x4.png", token).getBody();
        assertThat(servedPng).isEqualTo(pngBefore);
        assertThat(get("/api/artifacts/sync/pending", token).getBody()).contains("\"items\":[]");

        // 白名单之外（未登记清单的路径）不放行：远端不能变成任意读的口
        assertThat(getBytes("/api/artifacts/" + artifactId + "/file?path=../etc/passwd", token)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(getBytes("/api/artifacts/" + artifactId + "/file?path=not-in-manifest.md", token)
                .getStatusCode().value()).isEqualTo(404);
    }

    /** 写被拒：状态记 failed + 原因，file_state 必须原样不动，且对端报文里的凭证样字符串不外泄。 */
    @Test
    void writeFailureMarksSyncFailedWithoutTouchingFileState() throws Exception {
        String token = login("syncWriteFail");
        long artifactId = runPipeline(token);
        s3.failWrites = true;

        ResponseEntity<String> resp = post("/api/artifacts/" + artifactId + "/sync", "{}", token);
        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getBody()).contains("C0004").contains("对象存储返回 500").contains("InternalError");
        assertThat(resp.getBody()).doesNotContain(SECRET).doesNotContain("e2e-ak").doesNotContain("AKIA-LEAK-TEST");

        String detail = get("/api/artifacts/" + artifactId, token).getBody();
        assertThat(detail).contains("\"file_state\":\"complete\"") // 备份坏了 ≠ 稿子没了
                .contains("\"sync_state\":\"failed\"").contains("对象存储返回 500");
        // 待同步清单依然看得见它：失败不会悄悄从运维视野里消失
        assertThat(get("/api/artifacts/sync/pending", token).getBody()).contains("\"id\":" + artifactId);
    }

    /** 最阴的一种：PUT 全 200，回看却发现对象不在。不许因此把状态写成 synced。 */
    @Test
    void putAcceptedButAbsentObjectIsReportedFailure() throws Exception {
        String token = login("syncAbsent");
        long artifactId = runPipeline(token);
        s3.reportMissing = true;

        ResponseEntity<String> resp = post("/api/artifacts/" + artifactId + "/sync", "{}", token);
        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getBody()).contains("远端缺少对象");

        assertThat(get("/api/artifacts/" + artifactId, token).getBody())
                .contains("\"sync_state\":\"failed\"").contains("远端缺少对象");
        assertThat(s3.objects).isNotEmpty(); // 确实上传过，只是存在性核对没放过它
    }

    /** 远端"存在但被改坏"：读回落点必须校验 sha256，宁可 404 也不交付坏副本，并把状态标回去。 */
    @Test
    void corruptedRemoteCopyIsRejectedNotServed() throws Exception {
        String token = login("syncCorrupt");
        long artifactId = runPipeline(token);
        post("/api/artifacts/" + artifactId + "/sync", "{}", token);

        String objectKey = key(artifactId, "article.md");
        byte[] tampered = s3.objects.get(objectKey).clone();
        byte[] bad = new String(new String(tampered, StandardCharsets.UTF_8).replace("秋天", "冬天"))
                .getBytes(StandardCharsets.UTF_8);
        s3.objects.put(objectKey, bad);

        Files.delete(library.resolve(String.valueOf(artifactId)).resolve("article.md"));
        assertThat(getBytes("/api/artifacts/" + artifactId + "/file?path=article.md", token)
                .getStatusCode().value()).isEqualTo(404);

        String detail = get("/api/artifacts/" + artifactId, token).getBody();
        assertThat(detail).contains("\"sync_state\":\"failed\"").contains("远端副本校验不一致");
        // 好的一侧不受牵连：图片仍按清单从远端正常出字节
        assertThat(getBytes("/api/artifacts/" + artifactId + "/file?path=assets/cover-1-3x4.png", token).getBody())
                .isEqualTo(PNG);
    }

    /** 后台扫描的自愈路径：修好对端之后 sweep 补上缺失副本，待同步清单随之清空。 */
    @Test
    void sweepHealsFailedSyncAndEmptiesPendingList() throws Exception {
        String token = login("syncSweep");
        long artifactId = runPipeline(token);
        s3.failWrites = true;
        assertThat(post("/api/artifacts/" + artifactId + "/sync", "{}", token).getBody()).contains("C0004");
        assertThat(get("/api/artifacts/" + artifactId, token).getBody()).contains("\"sync_state\":\"failed\"");

        s3.failWrites = false;
        // sweep 不限属主（生产语义：一次扫描补齐全库），本类共用同一个库，所以只断言"至少补上 1 条"，
        // 真正的判据在下面：这条成品的状态翻回 synced、三个对象都在远端、待同步清单清空
        assertThat(syncService.sweep(20)).isGreaterThanOrEqualTo(1);

        String detail = get("/api/artifacts/" + artifactId, token).getBody();
        assertThat(detail).contains("\"sync_state\":\"synced\"");
        assertThat(s3.objects).containsKeys(key(artifactId, "article.md"), key(artifactId, "meta.json"),
                key(artifactId, "assets/cover-1-3x4.png"));
        assertThat(get("/api/artifacts/sync/pending", token).getBody()).contains("\"items\":[]");
    }

    /** 半套成品不许上远端：否则"本地丢了还能 repair"变成"远端也存了半套"。 */
    @Test
    void incompleteArtifactIsRefusedBySync() throws Exception {
        String token = login("syncHalf");
        long artifactId = runPipeline(token);
        Files.delete(library.resolve(String.valueOf(artifactId)).resolve("assets/cover-1-3x4.png"));
        assertThat(post("/api/artifacts/" + artifactId + "/repair", "{}", token).getBody())
                .contains("\"state\":\"incomplete\"");

        ResponseEntity<String> resp = post("/api/artifacts/" + artifactId + "/sync", "{}", token);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("本地成品未完整");
        assertThat(s3.objects).isEmpty();
        // 拒绝要留下痕迹（原因可查），但它不属于"待同步"运维面：半套成品的去处是 #12a 的核对列表
        assertThat(get("/api/artifacts/" + artifactId, token).getBody())
                .contains("\"file_state\":\"incomplete\"").contains("\"sync_state\":\"failed\"")
                .contains("本地成品未完整");
        assertThat(get("/api/artifacts/sync/pending", token).getBody()).contains("\"items\":[]");
        assertThat(get("/api/artifacts/incomplete", token).getBody()).contains("\"id\":" + artifactId);
    }

    /** 同步是写操作：非属主既看不见（A0404 统一口径），也不能借 sweep 之外的入口推别人的成品。 */
    @Test
    void syncRespectsOwnershipIsolation() throws Exception {
        String token = login("syncOwner");
        long artifactId = runPipeline(token);

        String other = login("syncVoyeur");
        assertThat(post("/api/artifacts/" + artifactId + "/sync", "{}", other).getBody()).contains("A0404");
        assertThat(get("/api/artifacts/sync/pending?limit=50", other).getBody()).contains("\"items\":[]");
        assertThat(getBytes("/api/artifacts/" + artifactId + "/file?path=article.md", other)
                .getStatusCode().value()).isEqualTo(404);
    }
}

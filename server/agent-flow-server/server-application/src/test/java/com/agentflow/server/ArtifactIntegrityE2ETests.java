package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.nodes.llm.LlmGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
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
 * P7-3#12a 成品库完整性核对与补偿 e2e。
 *
 * 要证的不是"repair 接口能调通"，而是三件容易悄悄说谎的事：
 * 1. 正常闭环的成品状态确实是 complete，核对列表是空的（不是"没实现所以看起来干净"）；
 * 2. 文本产物能从库里已有的 title/body/meta 逐字节重建——重建口径与落盘口径同源（ArticleMarkdown 单点），
 *    否则"补偿成功"会交付一份和原来不一样的稿子；
 * 3. 图片不在数据库里，缺了必须照实报缺、并把状态留在 incomplete。补偿不许把半套数据刷成绿色。
 *
 * 外部依赖全本地打桩，CI 无 key 也必须绿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-artifact-integrity;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=integrity-secret-at-least-32-chars!!!",
                "agentflow.security.rate-limit-per-minute=0",
                "agentflow.nodes.image.api-key=sk-e2e"})
class ArtifactIntegrityE2ETests {

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static HttpServer upstream;
    private static String upstreamBase;

    @TempDir
    static Path library;

    @DynamicPropertySource
    static void stubEndpoints(DynamicPropertyRegistry registry) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();
        upstream.createContext("/hot", ex -> json(ex, 200, "[{\"title\":\"秋天适合读的5本书\",\"hot\":\"420万\"}]"));
        upstream.createContext("/api/v1/services/aigc/text2image/image-synthesis", ex -> {
            ex.getRequestBody().readAllBytes();
            json(ex, 200, "{\"output\":{\"task_id\":\"integrity-task-1\"}}");
        });
        upstream.createContext("/api/v1/tasks/integrity-task-1", ex -> json(ex, 200,
                "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"url\":\""
                        + upstreamBase + "/img/cover.png\"}]}}"));
        upstream.createContext("/img/cover.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, PNG.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(PNG);
            }
        });
        upstream.start();

        registry.add("agentflow.nodes.image.base-url", () -> upstreamBase);
        registry.add("agentflow.content.library-dir", () -> library.toString());
    }

    @AfterAll
    static void stopStub() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 结构化成稿按【标记】协议返回，把 LLM 这个不确定变量从完整性验证里摘掉。 */
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

    @BeforeEach
    void resetLibrary() throws Exception {
        // @TempDir 在整个测试类生命周期内复用，上一轮残留的成品目录会让"核对列表为空"这条断言变成假绿
        try (var dirs = Files.list(library)) {
            for (Path p : dirs.toList()) {
                if (Files.isDirectory(p)) {
                    deleteTree(p);
                }
            }
        }
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

    private static String pipelineDsl() {
        return """
                {"name":"秋日读书归档闭环","nodes":[
                  {"id":"h","type":"hotspot.fetch","params":{"sources":"stub","limit":5,\
                "endpointOverride":"{\\"stub\\":\\"%s/hot\\"}"}},
                  {"id":"c","type":"copy.write","params":{"mode":"xiaohongshu","length":300}},
                  {"id":"i","type":"image.cover","params":{"kind":"cover","ratio":"3:4",\
                "pollIntervalMs":50,"maxWaitMs":5000}},
                  {"id":"p","type":"publish.local","params":{}}
                ],"edges":[{"from":"h","to":"c"},{"from":"c","to":"i"},{"from":"i","to":"p"}]}
                """.formatted(upstreamBase);
    }

    private long runPipeline(String token) throws Exception {
        long workflowId = Long.parseLong(extract(
                post("/api/workflows", pipelineDsl(), token).getBody(), "workflowId"));
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
        assertThat(list).contains("\"kind\":\"note\"");
        return Long.parseLong(extract(list, "id"));
    }

    @Test
    void healthyArtifactIsCompleteAndDeletedTextIsRebuiltByteIdentical() throws Exception {
        String token = login("integrityAuthor");
        long artifactId = runPipeline(token);

        // 正向基线：真跑完的成品状态是 complete，核对列表为空
        assertThat(get("/api/artifacts/" + artifactId, token).getBody())
                .contains("\"file_state\":\"complete\"");
        assertThat(get("/api/artifacts/incomplete", token).getBody()).contains("\"items\":[]");

        Path dir = library.resolve(String.valueOf(artifactId));
        // 正文文件名现在是 标题-yyyyMMddHHmmss.md，测试按目录实况取，不再钉死 article.md
        String mdName = markdownIn(dir);
        byte[] mdBefore = Files.readAllBytes(dir.resolve(mdName));
        byte[] metaBefore = Files.readAllBytes(dir.resolve("meta.json"));

        Files.delete(dir.resolve(mdName));
        Files.delete(dir.resolve("meta.json"));
        // 反向验证：下载接口真的读盘——文件没了就 404，不会拿 DB 里的正文冒充产物
        // （路径用前端实际传的原始形态；URL 编码过的 "/" 会被 RestTemplate 二次编码，让断言变成假绿）
        assertThat(getBytes("/api/artifacts/" + artifactId + "/file?path=" + mdName, token)
                .getStatusCode().value()).isEqualTo(404);

        ResponseEntity<String> repaired = post("/api/artifacts/" + artifactId + "/repair", "{}", token);
        assertThat(repaired.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(repaired.getBody()).contains("\"state\":\"complete\"")
                .contains(mdName).contains("meta.json");
        assertThat(markdownIn(dir)).isEqualTo(mdName); // 补偿必须按原名重建，不能又造一份新时刻的

        // 补偿交付的必须是同一份稿子，不是"重新生成一份像的"
        assertThat(Files.readAllBytes(dir.resolve(mdName))).isEqualTo(mdBefore);
        assertThat(Files.readAllBytes(dir.resolve("meta.json"))).isEqualTo(metaBefore);
        assertThat(new String(getBytes("/api/artifacts/" + artifactId
                + "/file?path=" + mdName, token).getBody(), StandardCharsets.UTF_8))
                .contains("秋天，把书一本本读完");

        // 图片产物没被误伤：补偿只重建文本，asset 仍在原位
        assertThat(Files.readAllBytes(dir.resolve("assets/cover-1-3x4.png"))).isEqualTo(PNG);
    }

    /** 成品目录里的那份 markdown（文件名带时刻，测试只认目录实况）。 */
    private static String markdownIn(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString()).sorted().findFirst()
                    .orElseThrow(() -> new AssertionError("目录里没有 markdown 产物: " + dir));
        }
    }

    /**
     * 图片不在数据库里，删掉后 repair 不能报"补好了"。
     * 这条是 #12a 的底线：补偿把不可见的半套数据变成可见、可再操作的数据，而不是把状态刷成绿色。
     */
    @Test
    void missingImageAssetIsReportedNotHidden() throws Exception {
        String token = login("integrityAuditor");
        long artifactId = runPipeline(token);
        Path dir = library.resolve(String.valueOf(artifactId));

        Files.delete(dir.resolve("assets/cover-1-3x4.png"));
        ResponseEntity<String> repaired = post("/api/artifacts/" + artifactId + "/repair", "{}", token);
        assertThat(repaired.getBody()).contains("\"state\":\"incomplete\"")
                .contains("assets/cover-1-3x4.png");

        // 状态真的回写到行上，并被完整性核对列表看见（这才是"可再操作"的证据）
        assertThat(get("/api/artifacts/" + artifactId, token).getBody())
                .contains("\"file_state\":\"incomplete\"")
                .contains("补偿后仍缺产物文件");
        assertThat(get("/api/artifacts/incomplete", token).getBody())
                .contains("\"items\":[{").contains("cover-1-3x4.png");

        // 缺的图不会被文本补偿伪造出来
        assertThat(getBytes("/api/artifacts/" + artifactId
                + "/file?path=assets/cover-1-3x4.png", token).getStatusCode().value()).isEqualTo(404);
    }

    /** 补偿是写操作：非属主既看不见（404 统一口径），也不能借 repair 探别人的目录。 */
    @Test
    void repairRespectsOwnershipIsolation() throws Exception {
        String token = login("integrityOwner");
        long artifactId = runPipeline(token);

        String other = login("integrityVoyeur");
        assertThat(post("/api/artifacts/" + artifactId + "/repair", "{}", other).getBody()).contains("A0404");
        assertThat(get("/api/artifacts/incomplete", other).getBody()).contains("\"items\":[]");
    }

    /**
     * 对象存储默认关（本类没配 agentflow.content.storage.*）：同步入口必须如实说"未启用"，
     * 待同步清单要带 enabled=false，而不是回一个空列表让人以为"远端都齐了"。
     * 关闭态也不建客户端——真凭证缺失时静默出网是最难查的配置事故。
     */
    @Test
    void syncSurfaceStaysClosedAndHonestWhenStorageDisabled() throws Exception {
        String token = login("integrityNoStorage");
        long artifactId = runPipeline(token);

        // enabled=false 是唯一的"为什么远端还没有"解释；列表仍如实列出这条成品（它确实一份都没推）
        assertThat(get("/api/artifacts/sync/pending", token).getBody())
                .contains("\"enabled\":false").contains("\"sync_state\":\"local\"")
                .contains("\"id\":" + artifactId);
        ResponseEntity<String> resp = post("/api/artifacts/" + artifactId + "/sync", "{}", token);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("对象存储未启用");
    }
}

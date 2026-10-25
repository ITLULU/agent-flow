package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.nodes.llm.LlmGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
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
 * P4 创作闭环 e2e：热榜 → 文案 → 主图 → 本地归档 → 小红书图文包，一条 DSL 跑通四个能力，
 * 再用成品库 API 证明"产物真的落盘、且只有属主能取"。
 *
 * 外部依赖全部本地打桩（模型走 @Primary 假网关，万相与热榜走本机 HttpServer）：
 * CI 上没有 key 也必须绿，这是企业级底座对测试的硬要求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p4-pipe;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=p4-pipe-secret-at-least-32-chars!!!",
                "agentflow.security.rate-limit-per-minute=0",
                "agentflow.nodes.image.api-key=sk-e2e",
                "agentflow.nodes.wechat.app-id=wx-e2e-appid",
                "agentflow.nodes.wechat.app-secret=secret-e2e"})
class P4CreationPipelineE2ETests {

    /** 1x1 PNG：用来证明"远端图已下载进内容库"，而不是只回了个会过期的 URL。 */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final String TITLE = "秋天，把书一本本读完";

    private static HttpServer upstream;
    private static String upstreamBase;
    private static final List<String> UPSTREAM_LOG = new ArrayList<>();

    @TempDir
    static Path library;

    /** 成品目录里的 markdown 名（带归档时刻，测试只认目录实况）。 */
    static String markdownOf(long artifactId) throws IOException {
        Path dir = library.resolve(String.valueOf(artifactId));
        try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString()).sorted().findFirst()
                    .orElseThrow(() -> new AssertionError("目录里没有 markdown 产物: " + dir));
        }
    }

    @DynamicPropertySource
    static void stubEndpoints(DynamicPropertyRegistry registry) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();

        upstream.createContext("/hot", ex -> {
            record("hot");
            json(ex, 200, "[{\"title\":\"秋天适合读的5本书\",\"hot\":\"420万\"}]");
        });
        upstream.createContext("/api/v1/services/aigc/text2image/image-synthesis", ex -> {
            record("submit:" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(ex, 200, "{\"output\":{\"task_id\":\"e2e-task-1\"}}");
        });
        upstream.createContext("/api/v1/tasks/e2e-task-1", ex -> {
            record("poll");
            json(ex, 200, "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"url\":\""
                    + upstreamBase + "/img/cover.png\"}]}}");
        });
        upstream.createContext("/img/cover.png", ex -> {
            record("download");
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, PNG.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(PNG);
            }
        });
        // 公众号草稿 API 桩：token → 封面素材 → draft/add
        upstream.createContext("/cgi-bin/token", ex -> {
            record("wx-token");
            json(ex, 200, "{\"access_token\":\"tok-e2e\",\"expires_in\":7200}");
        });
        upstream.createContext("/cgi-bin/material/add_material", ex -> {
            record("wx-material");
            json(ex, 200, "{\"media_id\":\"thumb-e2e\"}");
        });
        upstream.createContext("/cgi-bin/draft/add", ex -> {
            record("wx-draft:" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(ex, 200, "{\"errcode\":0,\"media_id\":\"draft-e2e\"}");
        });
        upstream.start();

        registry.add("agentflow.nodes.image.base-url", () -> upstreamBase);
        registry.add("agentflow.nodes.wechat.base-url", () -> upstreamBase);
        registry.add("agentflow.content.library-dir", () -> library.toString());
    }

    /** 假模型网关：按 copy.write 的【标记】协议返回结构化成稿，把 LLM 这个不确定变量从闭环验证里摘掉。 */
    @TestConfiguration
    static class FakeLlmConfig {

        @Bean
        @Primary
        LlmGateway fakeLlmGateway() {
            return request -> {
                String system = request.system() == null ? "" : request.system();
                // 渠道模板必须选对，否则"小红书文案"会按公众号长文写——这是创作节点最容易悄悄错的地方
                String body = system.contains("微信公众号")
                        ? "一、为什么秋天适合读书\n二、五本书的顺序\n三、怎么读才留得下来"
                        : "秋天翻书的声音比夏天好听。\n第一本适合在窗前读。\n第二本适合在地铁里读。";
                return new LlmGateway.Reply(
                        "【标题】" + TITLE + "\n"
                                + "【摘要】一份能直接照着读的秋日书单\n"
                                + "【标签】读书分享｜秋日书单｜自我成长｜阅读方法｜生活记录\n"
                                + "【金句】读完一本，才算真的过完一个秋天\n"
                                + "【封面文案】秋日读书清单｜5本\n"
                                + "【正文】" + body,
                        "fake-qwen", 123);
            };
        }
    }

    @AfterAll
    static void stopStub() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private static void record(String entry) {
        synchronized (UPSTREAM_LOG) {
            UPSTREAM_LOG.add(entry);
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

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> post(String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(bearer)), String.class);
    }

    private ResponseEntity<byte[]> getBytes(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(bearer)), byte[].class);
    }

    private static HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
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

    private static String pipelineDsl(String stubBase) {
        return """
                {"name":"秋日读书创作闭环","nodes":[
                  {"id":"h","type":"hotspot.fetch","params":{"sources":"stub","limit":5,\
                "endpointOverride":"{\\"stub\\":\\"%s/hot\\"}"}},
                  {"id":"c","type":"copy.write","params":{"mode":"xiaohongshu","length":300}},
                  {"id":"i","type":"image.cover","params":{"kind":"cover","ratio":"3:4",\
                "pollIntervalMs":50,"maxWaitMs":5000}},
                  {"id":"p","type":"publish.local","params":{}},
                  {"id":"x","type":"xhs.draft","params":{}}
                ],"edges":[{"from":"h","to":"c"},{"from":"c","to":"i"},{"from":"i","to":"p"},{"from":"p","to":"x"}]}
                """.formatted(stubBase);
    }

    /**
     * 轮询到**执行级终态**才返回：节点日志里的 status:SUCCESS 会先于整体完成出现，
     * 用字符串包含判断会读到半截执行（第一版就踩了这个坑），所以按顶层 status 字段判定。
     */
    private String awaitTerminal(long executionId, String token) throws Exception {
        String detail = null;
        for (int i = 0; i < 100; i++) {
            detail = get("/api/executions/" + executionId, token).getBody();
            if (detail != null) {
                String status = extract(detail, "status");
                if (!"RUNNING".equals(status) && !"QUEUED".equals(status)) {
                    return detail;
                }
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        return detail;
    }

    @Test
    void hotTopicToXhsPackageLandsInLibrary() throws Exception {
        String token = login("p4author");
        synchronized (UPSTREAM_LOG) {
            UPSTREAM_LOG.clear();
        }

        long workflowId = Long.parseLong(extract(
                post("/api/workflows", pipelineDsl(upstreamBase), token).getBody(), "workflowId"));
        long executionId = Long.parseLong(extract(
                post("/api/workflows/" + workflowId + "/executions", "{}", token).getBody(), "executionId"));

        String detail = awaitTerminal(executionId, token);
        assertThat(extract(detail, "status")).isEqualTo("SUCCESS");

        synchronized (UPSTREAM_LOG) {
            // 3:4 主图必须按小红书尺寸提交，并且真的把远端图取回过本地
            assertThat(UPSTREAM_LOG).anySatisfy(e -> assertThat(e).startsWith("submit:")
                    .contains("\"size\":\"768*1024\""));
            assertThat(UPSTREAM_LOG).contains("download");
        }

        // publish.local 出一条 note 成品；xhs.draft 另出一条 xhs-package 图文包
        String list = get("/api/artifacts?kind=note", token).getBody();
        assertThat(list).contains(TITLE).contains("\"kind\":\"note\"");
        long noteId = Long.parseLong(extract(list, "id"));

        assertThat(get("/api/artifacts/" + noteId, token).getBody()).contains("秋日读书清单").contains("读书分享");

        String files = get("/api/artifacts/" + noteId + "/files", token).getBody();
        String mdName = markdownOf(noteId); // {标题}-{yyyyMMddHHmmss}.md：重跑不覆盖上一版
        assertThat(mdName).matches("秋天把书一本本读完-\\d{14}\\.md"); // 标点从文件名里去掉，时刻完整保留
        assertThat(files).contains(mdName).contains("meta.json").contains("assets/cover-1-3x4.png")
                .doesNotContain("article.md");

        ResponseEntity<byte[]> md = getBytes("/api/artifacts/" + noteId + "/file?path=" + mdName, token);
        assertThat(md.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(new String(md.getBody(), StandardCharsets.UTF_8))
                .contains("# " + TITLE)
                .contains("读书分享");

        ResponseEntity<byte[]> png = getBytes("/api/artifacts/" + noteId + "/file?path=assets/cover-1-3x4.png", token);
        assertThat(png.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(png.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(png.getBody()).isEqualTo(PNG);

        // 穿越请求：白名单外的路径拿不到任何内容（404 且不回显目录结构）
        ResponseEntity<String> traversal = get("/api/artifacts/" + noteId + "/file?path="
                + URLEncoder.encode("../1/meta.json", StandardCharsets.UTF_8), token);
        assertThat(traversal.getBody()).contains("A0404").doesNotContain("meta_json");

        // 归属隔离：另一个用户既看不见也探不到别人的成品
        String other = login("p4voyeur");
        assertThat(get("/api/artifacts", other).getBody()).doesNotContain(TITLE);
        assertThat(get("/api/artifacts/" + noteId, other).getBody()).contains("A0404").doesNotContain(TITLE);

        // 落盘位置由 agentflow.content.library-dir 决定，产物目录就在它下面
        assertThat(library.resolve(String.valueOf(noteId))).isDirectory();
    }

    private static String wechatDsl() {
        return """
                {"name":"公众号长文闭环","nodes":[
                  {"id":"c","type":"copy.write","params":{"mode":"wechat","topic":"秋天适合把书读完","length":600}},
                  {"id":"i","type":"image.cover","params":{"kind":"cover","ratio":"wechat-share",\
                "pollIntervalMs":50,"maxWaitMs":5000}},
                  {"id":"p","type":"publish.local","params":{}},
                  {"id":"w","type":"wechat.draft","params":{}}
                ],"edges":[{"from":"c","to":"i"},{"from":"i","to":"p"},{"from":"p","to":"w"}]}
                """;
    }

    /**
     * 公众号通道闭环（用户选的发布渠道）：创作 → 分享大图 → 本地归档 → 草稿箱。
     * 关键证明点：草稿正文是**分段 HTML**（纯文本进微信会被压成一坨），封面来自 image.cover 而不是手填 URL。
     */
    @Test
    void wechatArticleClosesLoopIntoDraft() throws Exception {
        String token = login("p4writer");
        synchronized (UPSTREAM_LOG) {
            UPSTREAM_LOG.clear();
        }

        long workflowId = Long.parseLong(extract(
                post("/api/workflows", wechatDsl(), token).getBody(), "workflowId"));
        long executionId = Long.parseLong(extract(
                post("/api/workflows/" + workflowId + "/executions", "{}", token).getBody(), "executionId"));

        assertThat(extract(awaitTerminal(executionId, token), "status")).isEqualTo("SUCCESS");

        synchronized (UPSTREAM_LOG) {
            assertThat(UPSTREAM_LOG).anySatisfy(e -> assertThat(e).startsWith("submit:")
                    .contains("\"size\":\"1200*512\"")); // 公众号分享大图比例
            assertThat(UPSTREAM_LOG).contains("wx-material");
            assertThat(UPSTREAM_LOG).anySatisfy(e -> assertThat(e).startsWith("wx-draft:")
                    .contains(TITLE)
                    // 排版器交付的是内联样式正文（旧行为是一串裸 <p>，微信里没有任何层次）
                    .contains("<section style=").contains("一、为什么秋天适合读书")
                    .contains("linear-gradient")
                    .doesNotContain("class=")
                    .contains("thumb-e2e"));
        }

        String list = get("/api/artifacts?kind=article", token).getBody();
        assertThat(list).contains(TITLE);
    }
}

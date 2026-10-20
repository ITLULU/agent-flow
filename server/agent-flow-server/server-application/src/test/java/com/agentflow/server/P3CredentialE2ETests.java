package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P3 凭证库闭环 e2e：注册→登录→PUT 凭证（AES-GCM 落库）→ wechat.draft 带 credentialId 执行
 * → 本地微信桩必须收到"凭证库里的 appid"，且全局 env 凭证为空的情况下依然成功——证明端口打通。
 * 同时钉死：库中无明文、API 不回显秘密。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p3-cred;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=p3-cred-secret-at-least-32-chars!!!",
                "agentflow.security.rate-limit-per-minute=0",
                // 主密钥：base64("0123456789abcdef0123456789abcdef")，测试专用
                "agentflow.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                // 全局微信凭证留空：执行成功只能来自 credentialId 路径
                "agentflow.nodes.wechat.app-id=",
                "agentflow.nodes.wechat.app-secret="})
class P3CredentialE2ETests {

    private static HttpServer stub;
    private static final List<String> REQUEST_LOG = new ArrayList<>();

    @DynamicPropertySource
    static void wechatStubUrl(DynamicPropertyRegistry registry) throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/cgi-bin/token", ex -> {
            String query = ex.getRequestURI().getRawQuery();
            synchronized (REQUEST_LOG) {
                REQUEST_LOG.add("token?" + query);
            }
            byte[] body = "{\"access_token\":\"TOK-VAULT\",\"expires_in\":7200}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        stub.createContext("/cgi-bin/draft/add", ex -> {
            synchronized (REQUEST_LOG) {
                REQUEST_LOG.add("draft");
            }
            byte[] body = "{\"media_id\":\"DRAFT-VAULT-1\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        registry.add("agentflow.nodes.wechat.base-url",
                () -> "http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private ResponseEntity<String> post(String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> put(String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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

    @Test
    void vaultCredentialDrivesWechatExecution() throws Exception {
        post("/api/auth/register", "{\"username\":\"p3cred\",\"password\":\"passw0rd-123\"}", null);
        String token = extract(post("/api/auth/login",
                "{\"username\":\"p3cred\",\"password\":\"passw0rd-123\"}", null).getBody(), "token");

        // 1) 存凭证
        ResponseEntity<String> putResp = put("/api/credentials",
                "{\"provider\":\"wechat\",\"name\":\"main\",\"secrets\":{\"app_id\":\"vault-appid\",\"app_secret\":\"vault-secret-9999\"}}",
                token);
        // 响应只回元数据：provider/name 可见，而 "vault"（appid 与秘密的前缀）绝不回显
        assertThat(putResp.getBody()).contains("\"code\":\"0\"").contains("wechat")
                .doesNotContain("vault");
        long credentialId = Long.parseLong(extract(putResp.getBody(), "id"));

        // 2) 落库必须是密文：直接查表证明明文不可见
        String enc = jdbc.queryForObject(
                "SELECT secret_json_enc FROM user_credentials WHERE id = ?", String.class, credentialId);
        assertThat(enc).doesNotContain("vault-appid").doesNotContain("vault-secret-9999").isNotBlank();

        // 3) API 列表不回显秘密
        String list = get("/api/credentials", token).getBody();
        assertThat(list).contains("\"provider\":\"wechat\"").doesNotContain("vault-secret-9999");

        // 4) 带 credentialId 执行——全局 env 凭证为空，成功只能来自凭证库
        String dsl = """
                {"name":"凭证库发布","nodes":[{"id":"w","type":"wechat.draft",
                 "params":{"credentialId":CRED,"title":"T","content":"正文"}}],"edges":[]}
                """.replace("CRED", String.valueOf(credentialId));
        long workflowId = Long.parseLong(extract(post("/api/workflows", dsl, token).getBody(), "workflowId"));
        long executionId = Long.parseLong(extract(
                post("/api/workflows/" + workflowId + "/executions", "{}", token).getBody(), "executionId"));

        String detail = null;
        for (int i = 0; i < 40; i++) {
            detail = get("/api/executions/" + executionId, token).getBody();
            if (detail != null && (detail.contains("\"SUCCESS\"") || detail.contains("FAILED"))) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }
        assertThat(detail).contains("SUCCESS").contains("DRAFT-VAULT-1")
                .doesNotContain("DEGRADED"); // 发布节点永不降级，成功就是真成功

        // 5) 桩侧证据：token 请求带的是凭证库里的 appid
        synchronized (REQUEST_LOG) {
            assertThat(REQUEST_LOG).anySatisfy(entry ->
                    assertThat(entry).startsWith("token?").contains("appid=vault-appid"));
        }
    }

    @Test
    void unknownCredentialIdFailsFastWithoutRetry() throws Exception {
        String token = "unused";
        post("/api/auth/register", "{\"username\":\"p3cred2\",\"password\":\"passw0rd-123\"}", null);
        token = extract(post("/api/auth/login",
                "{\"username\":\"p3cred2\",\"password\":\"passw0rd-123\"}", null).getBody(), "token");

        String dsl = """
                {"name":"幽灵凭证","nodes":[{"id":"w","type":"wechat.draft",
                 "params":{"credentialId":999999,"title":"T","content":"正文"}}],"edges":[]}""";
        long workflowId = Long.parseLong(extract(post("/api/workflows", dsl, token).getBody(), "workflowId"));
        long executionId = Long.parseLong(extract(
                post("/api/workflows/" + workflowId + "/executions", "{}", token).getBody(), "executionId"));

        String detail = null;
        for (int i = 0; i < 20; i++) {
            detail = get("/api/executions/" + executionId, token).getBody();
            if (detail != null && detail.contains("FAILED")) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        // A0400 配置类错误：不可重试（attempts=1），原因可见
        assertThat(detail).contains("FAILED").contains("credentialId=999999").contains("\"attempts\":1");
    }
}

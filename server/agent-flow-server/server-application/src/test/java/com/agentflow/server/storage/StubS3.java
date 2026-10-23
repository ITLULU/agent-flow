package com.agentflow.server.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用的 S3 兼容桩：只实现成品同步真正会发出的四个动作（PUT/GET/HEAD/DELETE），
 * 并把**线上原样路径**与请求头留档，用来钉住"编码只做一次""404 语义""错误不外泄凭证"这些口径。
 *
 * 不校验签名（那由真 MinIO/AWS 负责），但要求请求带 Authorization——没有它测试会通过得毫无意义。
 */
public final class StubS3 implements AutoCloseable {

    private final HttpServer server;
    private final String base;
    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    public final List<String> log = new CopyOnWriteArrayList<>();
    public final List<Map<String, String>> headerLog = new CopyOnWriteArrayList<>();

    /** 打开后所有写请求回 500（带 S3 报文），用来验证"备份失败"如何落状态。 */
    public volatile boolean failWrites;

    /** HEAD/GET 是否报告对象存在：关掉用来制造"PUT 成功但对象不在"这种最阴的假成功。 */
    public volatile boolean reportMissing = false;

    /** 建桶只发生在上下文启动时，而 log 每条用例都会清空——用一个不会被清掉的标记才证明确实探过。 */
    private volatile boolean bucketCreated;

    public StubS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
    }

    public String base() {
        return base;
    }

    public boolean bucketCreated() {
        return bucketCreated;
    }

    /** 对象键 = "/bucket/" 之后的部分，按解码后的形态存，便于跨编码比Round-trip。 */
    private static String objectKey(String rawPath) {
        int slash = rawPath.indexOf('/', 1);
        if (slash < 0 || slash == rawPath.length() - 1) {
            return "";
        }
        return URLDecoder.decode(rawPath.substring(slash + 1), StandardCharsets.UTF_8);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String rawPath = ex.getRequestURI().getRawPath();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        log.add(method + " " + rawPath);
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        headerLog.add(headers);
        byte[] body = ex.getRequestBody().readAllBytes();

        if (method.equals("PUT") && objectKey(rawPath).isEmpty()) {
            bucketCreated = true;
            log.add("PUTBUCKET");
            respond(ex, 200, new byte[0]);
            return;
        }
        String key = objectKey(rawPath);
        switch (method) {
            case "PUT" -> {
                if (auth == null || !auth.startsWith("AWS4-HMAC-SHA256 ")) {
                    respond(ex, 403, xml("AccessDenied", "missing signature"));
                    return;
                }
                if (failWrites) {
                    respond(ex, 500, xml("InternalError", "stub 故障"));
                    return;
                }
                objects.put(key, body);
                respond(ex, 200, new byte[0]);
            }
            case "GET" -> {
                byte[] stored = objects.get(key);
                if (stored == null || reportMissing) {
                    respond(ex, 404, xml("NoSuchKey", "no such object"));
                    return;
                }
                respond(ex, 200, stored);
            }
            case "HEAD" -> {
                boolean present = objects.containsKey(key) && !reportMissing;
                ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(present ? 200 : 404, -1);
                ex.close();
            }
            case "DELETE" -> {
                byte[] removed = objects.remove(key);
                respond(ex, removed == null ? 404 : 204, new byte[0]);
            }
            default -> respond(ex, 405, xml("MethodNotAllowed", method));
        }
    }

    /** 错误报文里刻意带上凭证样字符串，用来证明客户端不会把对端报文原样抛进日志/响应。 */
    private static byte[] xml(String code, String message) {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code>"
                + "<Message>" + message + "</Message>"
                + "<AccessKeyId>AKIA-LEAK-TEST</AccessKeyId></Error>";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/xml");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

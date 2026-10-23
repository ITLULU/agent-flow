package com.agentflow.server.storage;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.server.config.ObjectStorageProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S3 兼容对象存储客户端（MinIO / OSS S3 端点 / AWS），路径风格寻址，纯 JDK HttpClient + {@link SigV4}。
 *
 * 只覆盖成品同步真正需要的四个动作（PUT/GET/HEAD/建桶），不做分片上传：
 * 产物是"一篇稿 + 几张图"，单对象上限按 64MB 拦，超过就是配置错或用错了存储，不值得为它引入 SDK。
 *
 * 错误信息里只带 HTTP 状态与 S3 错误码（{@code <Code>}），绝不回显请求头、密钥或对端完整报文——
 * S3 的 AccessDenied 报文里会带上被拒绝的凭证标识，日志里出现它等于把 AKID 撒出去。
 */
public class S3ObjectStore implements ObjectStore {

    private static final Pattern S3_CODE = Pattern.compile("<Code>([^<]{1,64})</Code>");
    private static final long MAX_OBJECT_BYTES = 64L * 1024 * 1024;

    private final HttpClient http;
    private final String scheme;
    private final String host;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final Duration requestTimeout;

    public S3ObjectStore(ObjectStorageProperties props) {
        URI base = URI.create(props.endpoint().trim());
        this.scheme = base.getScheme();
        this.host = base.getPort() > 0 ? base.getAuthority() : base.getHost();
        this.bucket = props.bucket().trim();
        this.region = props.region();
        this.accessKey = props.accessKey();
        this.secretKey = props.secretKey();
        this.requestTimeout = Duration.ofMillis(props.requestTimeoutMs());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER) // 重定向会换 host，签名口径就变了 ⇒ 一律不跟
                .build();
    }

    @Override
    public void put(String key, byte[] body, String contentType) {
        if (body.length > MAX_OBJECT_BYTES) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "产物单文件超过对象存储上限 64MB: " + briefKey(key) + "（" + body.length + " 字节）");
        }
        send("PUT", objectPath(key), body, Map.of("content-type", contentType));
    }

    @Override
    public byte[] get(String key) {
        Response res = send("GET", objectPath(key), new byte[0], Map.of());
        return res.status() == 404 ? null : res.body();
    }

    @Override
    public boolean exists(String key) {
        return send("HEAD", objectPath(key), new byte[0], Map.of()).status() < 400;
    }

    @Override
    public void delete(String key) {
        // DELETE 语义幂等：404 = 本来就没有，不当故障抛
        send("DELETE", objectPath(key), new byte[0], Map.of());
    }

    @Override
    public void ensureBucket() {
        String path = "/" + bucket + "/";
        if (headBucket(path)) {
            return;
        }
        try {
            send("PUT", path, new byte[0], Map.of());
        } catch (ApiException e) {
            // 已存在/并发创建都会以非 2xx 回来；以"现在能 HEAD 通"为准，不把已就绪的桶报成故障
            if (!headBucket(path)) {
                throw e;
            }
        }
    }

    private boolean headBucket(String path) {
        try {
            return send("HEAD", path, new byte[0], Map.of()).status() < 400;
        } catch (ApiException e) {
            return false;
        }
    }

    /** 线上传输的 key 可以含空格与中文；编码口径由 {@link SigV4#canonicalUri} 单点保证与签名一致。 */
    private String objectPath(String key) {
        String cleaned = key == null ? "" : key.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "对象键不能为空");
        }
        return "/" + bucket + "/" + cleaned;
    }

    private Response send(String method, String rawPath, byte[] body, Map<String, String> extraHeaders) {
        String wirePath = SigV4.canonicalUri(rawPath);
        URI uri = URI.create(scheme + "://" + host + wirePath);
        Map<String, String> signed = SigV4.sign(method, host, rawPath, "", extraHeaders, body,
                accessKey, secretKey, region, Instant.now());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        signed.forEach(builder::header);
        extraHeaders.forEach(builder::header);

        Response response;
        try {
            response = exchange(builder.build());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // 只报异常类型：底层消息可能含 URL（URL 里有 bucket，但不含密钥；仍然收敛掉更稳）
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "对象存储请求未能发出: " + e.getClass().getSimpleName());
        }
        // 这些方法的 404 是业务语义（对象/桶不存在、本来就没得删），不是故障
        boolean tolerated404 = response.status() == 404
                && (method.equals("HEAD") || method.equals("GET") || method.equals("DELETE"));
        if (response.status() >= 400 && !tolerated404) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "对象存储返回 " + response.status() + " " + s3Code(response.body()) + "（" + method
                            + " " + briefKey(rawPath) + "）");
        }
        return response;
    }

    private Response exchange(HttpRequest request) throws Exception {
        HttpResponse<byte[]> raw = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Response(raw.statusCode(), raw.body() == null ? new byte[0] : raw.body());
    }

    private static String s3Code(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        Matcher m = S3_CODE.matcher(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : "";
    }

    /** 日志与错误里只留路径尾段，避免把整棵成品目录结构写进响应体。 */
    private static String briefKey(String path) {
        String p = path == null ? "" : path;
        return p.length() <= 96 ? p : "…" + p.substring(p.length() - 96);
    }

    private record Response(int status, byte[] body) {
    }
}

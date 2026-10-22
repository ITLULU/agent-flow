package com.agentflow.nodes.support;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 节点层统一出网工具（skill §3：出网点四件套写成工具，禁止逐处手搓）。
 *
 * 异常口径与既有节点完全一致，便于执行日志聚合：
 * 连接拒绝/域名解析失败 → C0001(UPSTREAM_REJECTED)，读取超时 → C0002(UPSTREAM_TIMEOUT)，
 * 非 2xx / 响应非 JSON → C0003(UPSTREAM_PROTOCOL_ERROR)。参数非法 → A0400（不可重试）。
 *
 * 注意：这里不设重试与降级——两者归节点层决策（有的节点要降级，有的必须失败）。
 */
public final class HttpJson {

    public static final String UA = "Mozilla/5.0 (compatible; agentflow-node/0.1)";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final Duration callTimeout;

    public HttpJson(Duration callTimeout) {
        this.callTimeout = callTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public String get(String url) {
        return get(url, new String[0]);
    }

    /** headerKv 形如 {"Authorization", "Bearer x"}，可为 null。 */
    public String get(String url, String... headerKv) {
        return send(build("GET", url, null, headerKv), url);
    }

    public String postJson(String url, String jsonBody, String... headerKv) {
        return send(build("POST", url, jsonBody, headerKv), url);
    }

    public JsonNode getJson(String url) {
        return readJson(get(url), url);
    }

    /** Bearer 鉴权的 GET（DashScope 任务轮询等）。 */
    public JsonNode getJsonWithAuth(String url, String bearerToken) {
        return readJson(get(url, "Authorization", "Bearer " + bearerToken), url);
    }

    public JsonNode postJsonAndGet(String url, String jsonBody, String... headerKv) {
        return readJson(postJson(url, jsonBody, headerKv), url);
    }

    /** 取原始字节（下载图片等），异常口径同上。 */
    public byte[] getBytes(String url) {
        HttpRequest req = build("GET", url, null);
        try {
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(resp.statusCode(), url);
            return resp.body();
        } catch (HttpConnectTimeoutException e) {
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接超时");
        } catch (HttpTimeoutException e) {
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, url + " 读取超时");
        } catch (ConnectException | UnknownHostException e) {
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接被拒绝: " + causeText(e));
        } catch (IOException e) {
            throw reclassify(e, url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, url + " 执行被中断");
        }
    }

    private HttpRequest build(String method, String url, String body, String... headerKv) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "url 不合法: " + url);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(callTimeout) // 与节点 timeout() 同口径的兜底超时，线程可被引擎释放
                .header("User-Agent", UA);
        if (headerKv != null) {
            for (int i = 0; i + 1 < headerKv.length; i += 2) {
                if (headerKv[i] != null && headerKv[i + 1] != null) {
                    b.header(headerKv[i], headerKv[i + 1]);
                }
            }
        }
        b.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return b.build();
    }

    private String send(HttpRequest req, String url) {
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接超时");
        } catch (HttpTimeoutException e) {
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, url + " 读取超时");
        } catch (ConnectException | UnknownHostException e) {
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接被拒绝: " + causeText(e));
        } catch (IOException e) {
            throw reclassify(e, url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, url + " 执行被中断");
        }
        checkStatus(resp.statusCode(), url);
        return resp.body();
    }

    private static void checkStatus(int status, String url) {
        if (status / 100 != 2) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, url + " HTTP状态 " + status);
        }
    }

    private static ApiException reclassify(IOException e, String url) {
        Throwable c = e.getCause();
        if (c instanceof ConnectException || c instanceof UnknownHostException) {
            return new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接被拒绝: " + causeText(c));
        }
        if (c instanceof HttpConnectTimeoutException) {
            return new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接超时");
        }
        if (c instanceof HttpTimeoutException) {
            return new ApiException(ErrorCode.UPSTREAM_TIMEOUT, url + " 读取超时");
        }
        return new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " IO异常: " + e.getMessage());
    }

    private static JsonNode readJson(String body, String url) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR,
                    url + " 响应非 JSON: " + brief(body));
        }
    }

    public static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static String causeText(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}

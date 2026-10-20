package com.agentflow.nodes.http;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * http.collect — 网页采集节点（jsoup 解析）。
 *
 * params: url(必填) / maxChars(默认8000) / cssSelector(可选) / attr(可选，配合 selector 取属性如 href)
 * 输出: url / title / text(截断) / items(selector 命中列表，最多50) / fetchedAt
 *
 * 四件套口径：
 * - 超时：引擎层 timeout()=20s 唯一口径；内部 HttpRequest.timeout 取同值兜底（引擎超时后线程可释放），不另立数值。
 * - 重试：2 次 + 1s 指数退避（瞬时网络抖动可重试，配置错误走 A0400 不可重试）。
 * - 异常：连接拒绝→C0001、超时→C0002、协议/非2xx→C0003，禁止原始堆栈外抛。
 * - 降级：重试耗尽后输出空正文续跑（多源采集时单源失败不拖垮整条流水线），degraded=true 显性。
 */
public class HttpCollectNodeExecutor implements NodeExecutor {

    public static final String TYPE = "http.collect";
    private static final String UA = "Mozilla/5.0 (compatible; agentflow-collector/0.1)";
    private static final int MAX_ITEMS = 50;

    private final HttpClient http;
    private final Duration callTimeout;

    public HttpCollectNodeExecutor() {
        this(Duration.ofSeconds(15));
    }

    /** callTimeout 仅供测试收紧；生产保持与 timeout() 同口径的默认值。 */
    public HttpCollectNodeExecutor(Duration callTimeout) {
        this.callTimeout = callTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(20);
    }

    @Override
    public RetryPolicy retryPolicy() {
        return new RetryPolicy(2, 1000L);
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        String url = input.paramAsString("url", null);
        if (url == null || url.isBlank()) {
            // 参数缺失是定义问题，重试无意义——A0400 不可重试
            throw new ApiException(ErrorCode.BAD_REQUEST, "http.collect 缺少必填参数 url");
        }
        int maxChars = asInt(input.param("maxChars"), 8000);
        String cssSelector = input.paramAsString("cssSelector", null);
        String attr = input.paramAsString("attr", null);

        String html = fetch(url);

        org.jsoup.nodes.Document doc = Jsoup.parse(html, url);
        Map<String, Object> data = new HashMap<>();
        data.put("url", url);
        data.put("title", doc.title());
        data.put("text", truncate(doc.body() == null ? "" : doc.body().text(), maxChars));
        if (cssSelector != null && !cssSelector.isBlank()) {
            Elements els = doc.select(cssSelector);
            List<String> items = new ArrayList<>();
            for (Element el : els) {
                if (items.size() >= MAX_ITEMS) {
                    break;
                }
                items.add(attr == null || attr.isBlank() ? el.text() : el.attr(attr));
            }
            data.put("items", items);
        }
        data.put("fetchedAt", System.currentTimeMillis());
        return NodeOutput.ok(data);
    }

    @Override
    public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
        Map<String, Object> data = new HashMap<>();
        data.put("url", input.paramAsString("url", ""));
        data.put("title", "");
        data.put("text", "");
        data.put("items", List.of());
        return Optional.of(NodeOutput.degraded(data, "采集失败，跳过该来源: " + safeMessage(lastError)));
    }

    private String fetch(String url) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(callTimeout) // 与引擎同口径的兜底超时，见类注释
                    .header("User-Agent", UA)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "url 不合法: " + e.getMessage());
        }
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接超时");
        } catch (HttpTimeoutException e) {
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, url + " 读取超时");
        } catch (ConnectException | UnknownHostException e) {
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接被拒绝: " + causeText(e));
        } catch (IOException e) {
            Throwable c = e.getCause();
            if (c instanceof ConnectException || c instanceof UnknownHostException) {
                throw new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接被拒绝: " + causeText(c));
            }
            if (c instanceof HttpConnectTimeoutException) {
                throw new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " 连接超时");
            }
            if (c instanceof HttpTimeoutException) {
                throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, url + " 读取超时");
            }
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, url + " IO异常: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, url + " 执行被中断");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, url + " HTTP状态 " + resp.statusCode());
        }
        return new String(resp.body(), StandardCharsets.UTF_8);
    }

    private static String truncate(String text, int maxChars) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...[截断]";
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** ConnectException.getMessage() 可能为 null，避免日志里出现"拒绝: null"。 */
    private static String causeText(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}

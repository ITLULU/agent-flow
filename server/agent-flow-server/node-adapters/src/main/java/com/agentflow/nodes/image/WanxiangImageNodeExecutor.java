package com.agentflow.nodes.image;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * image.wanxiang — 通义万相文生图节点（DashScope 原生异步协议：提交 task + 轮询）。
 *
 * params: prompt(必填) / model(默认 wanx2.1-t2i-turbo) / size(默认 1024*1024)
 *         pollIntervalMs(默认1500) / maxWaitMs(默认60000，节点内任务等待上限)
 * 输出: imageUrl / taskId / model / costMs
 *
 * 四件套口径（生图特有的钱敏感设计）：
 * - 超时：引擎层 120s；maxWaitMs 再封顶任务轮询窗口，双保险不冲突（节点内是业务等待，非连接超时）。
 * - 重试：noRetry——引擎重试会二次提交异步生图任务 = 重复计费，宁可直接失败。已用单测钉死。
 * - 异常：连接拒绝→C0001、超时/等待超限→C0002、HTTP非2xx或task FAILED→C0003。
 * - 降级：输出无图（imageUrl 空串）续跑——公众号可发纯文图文，degraded=true 显性。
 */
public class WanxiangImageNodeExecutor implements NodeExecutor {

    public static final String TYPE = "image.wanxiang";
    private static final String SUBMIT_PATH = "/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_PATH = "/api/v1/tasks/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final Duration callTimeout;

    public WanxiangImageNodeExecutor(String apiKey) {
        this(apiKey, "https://dashscope.aliyuncs.com", Duration.ofSeconds(30));
    }

    /** baseUrl/callTimeout 为测试与专有云端点留口子；生产用单参构造。 */
    public WanxiangImageNodeExecutor(String apiKey, String baseUrl, Duration callTimeout) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.callTimeout = callTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(120);
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.noRetry(); // 防重复计费，勿改
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "生图未配置（缺少 BAILIAN_API_KEY），image.wanxiang 无法执行");
        }
        String prompt = input.paramAsString("prompt", null);
        if (prompt == null || prompt.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "image.wanxiang 缺少必填参数 prompt");
        }
        String model = input.paramAsString("model", "wanx2.1-t2i-turbo");
        String size = input.paramAsString("size", "1024*1024");
        long pollIntervalMs = asLong(input.param("pollIntervalMs"), 1500L);
        long maxWaitMs = asLong(input.param("maxWaitMs"), 60_000L);

        long started = System.currentTimeMillis();
        String taskId = submit(model, prompt, size);
        String imageUrl = pollUntilDone(taskId, pollIntervalMs, maxWaitMs);

        Map<String, Object> data = new HashMap<>();
        data.put("imageUrl", imageUrl);
        data.put("taskId", taskId);
        data.put("model", model);
        data.put("costMs", System.currentTimeMillis() - started);
        return NodeOutput.ok(data);
    }

    @Override
    public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
        Map<String, Object> data = new HashMap<>();
        data.put("imageUrl", "");
        data.put("prompt", input.paramAsString("prompt", ""));
        return Optional.of(NodeOutput.degraded(data, "生图失败，按无图发布: " + safeMessage(lastError)));
    }

    private String submit(String model, String prompt, String size) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.putObject("input").put("prompt", prompt);
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("n", 1);
        parameters.put("size", size);
        JsonNode json = request("POST", SUBMIT_PATH, body.toString(), true);
        JsonNode taskId = json.path("output").path("task_id");
        if (taskId.isMissingNode() || taskId.asText().isBlank()) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "提交响应缺少 task_id: " + brief(json));
        }
        return taskId.asText();
    }

    private String pollUntilDone(String taskId, long pollIntervalMs, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (true) {
            JsonNode json = request("GET", TASK_PATH + taskId, null, false);
            JsonNode output = json.path("output");
            String status = output.path("task_status").asText("");
            switch (status) {
                case "SUCCEEDED" -> {
                    JsonNode results = output.path("results");
                    String url = results.isArray() && results.size() > 0
                            ? results.get(0).path("url").asText("")
                            : "";
                    if (url.isBlank()) {
                        throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "任务成功但无图片 URL: " + brief(json));
                    }
                    return url;
                }
                case "FAILED" -> throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR,
                        "生图任务失败: " + output.path("code").asText("?") + " " + output.path("message").asText(""));
                default -> {
                    // PENDING / RUNNING：继续轮询
                }
            }
            if (System.currentTimeMillis() + pollIntervalMs > deadline) {
                throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT,
                        "生图任务等待超限(" + maxWaitMs + "ms): taskId=" + taskId);
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "轮询被中断: taskId=" + taskId);
            }
        }
    }

    private JsonNode request(String method, String path, String body, boolean asyncHeader) {
        URI uri;
        try {
            uri = URI.create(baseUrl + path);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "baseUrl 不合法: " + baseUrl);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(callTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
        if (asyncHeader) {
            builder.header("X-DashScope-Async", "enable"); // 异步协议头：缺失则 DashScope 同步挂长连接
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, "DashScope 连接超时");
        } catch (HttpTimeoutException e) {
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "DashScope 读取超时");
        } catch (ConnectException | UnknownHostException e) {
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, "DashScope 连接被拒绝: " + e.getMessage());
        } catch (IOException e) {
            Throwable c = e.getCause();
            if (c instanceof ConnectException || c instanceof UnknownHostException) {
                throw new ApiException(ErrorCode.UPSTREAM_REJECTED, "DashScope 连接被拒绝: " + c.getMessage());
            }
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, "DashScope IO异常: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "DashScope 调用被中断");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR,
                    "DashScope HTTP " + resp.statusCode() + ": " + briefText(resp.body()));
        }
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "DashScope 响应非 JSON: " + briefText(resp.body()));
        }
    }

    private static String brief(JsonNode node) {
        String s = node.toString();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static String briefText(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }

    private static long asLong(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}

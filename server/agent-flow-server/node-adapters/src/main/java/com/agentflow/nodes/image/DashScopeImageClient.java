package com.agentflow.nodes.image;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.nodes.support.HttpJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * DashScope 万相文生图客户端（异步协议：提交 task → 轮询 → 取图）。
 * image.cover 专用；既有 image.wanxiang 保持原实现不动（其契约数值由单测钉住，避免无谓改动面）。
 *
 * 异常全部按 HttpJson 三类口径转译（C0001/C0002/C0003），任务态失败归 C0003。
 */
public class DashScopeImageClient {

    private static final String SUBMIT_PATH = "/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_PATH = "/api/v1/tasks/";

    private final HttpJson http;
    private final String apiKey;
    private final String baseUrl;

    public DashScopeImageClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.http = new HttpJson(java.time.Duration.ofSeconds(30));
    }

    /** @return task_id */
    public String submit(String model, String prompt, String size, int n) {
        String body = "{\"model\":\"" + model + "\",\"input\":{\"prompt\":\"" + escape(prompt)
                + "\"},\"parameters\":{\"n\":" + n + ",\"size\":\"" + size + "\",\"prompt_extend\":true}}";
        JsonNode json = http.postJsonAndGet(baseUrl + SUBMIT_PATH, body,
                "Authorization", "Bearer " + apiKey, "X-DashScope-Async", "enable");
        JsonNode taskId = json.path("output").path("task_id");
        if (taskId.isMissingNode() || taskId.asText().isBlank()) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "提交响应缺少 task_id: " + HttpJson.brief(json.toString()));
        }
        return taskId.asText();
    }

    /** 轮询直到 SUCCEEDED/FAILED/超时；返回 results[].url（数量可能少于请求的 n，调用方需容忍）。 */
    public List<String> awaitUrls(String taskId, long pollIntervalMs, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (true) {
            JsonNode json = http.getJsonWithAuth(baseUrl + TASK_PATH + taskId, apiKey);
            JsonNode output = json.path("output");
            String status = output.path("task_status").asText("");
            if ("SUCCEEDED".equals(status)) {
                List<String> urls = new ArrayList<>();
                for (JsonNode r : output.path("results")) {
                    String u = r.path("url").asText("");
                    if (!u.isBlank()) {
                        urls.add(u);
                    }
                }
                if (urls.isEmpty()) {
                    throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR,
                            "任务成功但无图片 URL: " + HttpJson.brief(json.toString()));
                }
                return urls;
            }
            if ("FAILED".equals(status)) {
                throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "生图任务失败: "
                        + output.path("code").asText("?") + " " + output.path("message").asText(""));
            }
            if (System.currentTimeMillis() + pollIntervalMs > deadline) {
                throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "生图任务等待超限(" + maxWaitMs + "ms): taskId=" + taskId);
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "轮询被中断: taskId=" + taskId);
            }
        }
    }

    /** 万相 URL 有效期约 24h，必须落地才算交付物——这里把远端图取回本地。 */
    public byte[] download(String url) {
        return http.getBytes(url);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}

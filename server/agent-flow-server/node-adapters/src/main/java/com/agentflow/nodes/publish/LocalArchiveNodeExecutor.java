package com.agentflow.nodes.publish;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.spi.ContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * publish.local — 本地内容库归档节点（自动化发布的第一站：先落库落盘，再谈外发）。
 *
 * 上游取数：找带 body 的上游输出（copy.write）作正文，找带 images 的输出（image.cover）作图。
 * params: kind(article|note，缺省时按 copy.write 的 mode 推) / slug(可选，缺省用标题规范化)
 *         title/body/digest 可显式覆盖上游
 * 输出: artifactId / dir / files[] / markdownPath
 *
 * 四件套口径：
 * - 超时：30s（本地盘 + 一条 insert，超过就是环境异常）。
 * - 重试：noRetry——重试会插入第二条成品行，宁可失败也别脏数据（幂等由 artifactId 天然唯一，但业务上不想看到重复稿）。
 * - 异常：ContentStore 未装配 → A0400 配置错；磁盘写入失败 → B0001（内部错，不外露堆栈）。
 * - 降级：不降级。归档即交付，静默丢稿是最坏结果。
 */
public class LocalArchiveNodeExecutor implements NodeExecutor {

    public static final String TYPE = "publish.local";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContentStore contentStore;

    public LocalArchiveNodeExecutor(ContentStore contentStore) {
        this.contentStore = contentStore;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(30);
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.noRetry();
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        if (contentStore == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "publish.local 未装配内容库（ContentStore 缺席）");
        }
        Map<String, Object> copy = findUpstream(input, "body");
        Map<String, Object> image = findUpstream(input, "images");

        String title = firstNonBlank(input.paramAsString("title", null), str(copy.get("title")), "未命名-" + ctx.executionId());
        String body = firstNonBlank(input.paramAsString("body", null), str(copy.get("body")));
        if (body.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "publish.local 无正文可归档：上游无 copy.write 输出且未传 body 参数");
        }
        String mode = str(copy.get("mode"));
        String kind = firstNonBlank(input.paramAsString("kind", null),
                "xiaohongshu".equals(mode) ? "note" : "article");

        Map<String, Object> meta = new HashMap<>();
        meta.put("mode", mode);
        meta.put("digest", copy.get("digest"));
        meta.put("hashtags", copy.getOrDefault("hashtags", List.of()));
        meta.put("keyPoints", copy.getOrDefault("keyPoints", List.of()));
        meta.put("coverLines", copy.getOrDefault("coverLines", List.of()));
        meta.put("images", image.getOrDefault("images", List.of()));
        meta.put("traceId", ctx.traceId());
        meta.put("workflowId", ctx.workflowId());
        meta.put("sourceUrl", firstNonBlank(str(copy.get("url")), input.paramAsString("sourceUrl", null)));

        long artifactId = contentStore.insert(ctx.executionId(), kind, title, body, toJson(meta));

        List<String> files = new ArrayList<>();
        files.add(contentStore.writeText(artifactId, "article.md",
                markdown(kind, title, str(copy.get("digest")), body, list(copy.get("hashtags")))));
        files.add(contentStore.writeText(artifactId, "meta.json", prettyJson(meta)));
        files.addAll(contentStore.attachStaged(ctx.executionId(), artifactId));

        Map<String, Object> data = new HashMap<>();
        data.put("artifactId", artifactId);
        data.put("kind", kind);
        data.put("title", title);
        data.put("dir", contentStore.artifactDir(artifactId));
        data.put("files", files);
        data.put("fileCount", files.size());
        data.put("archivedAt", LocalDate.now().toString());
        return NodeOutput.ok(data);
    }

    private static String markdown(String kind, String title, String digest, String body, List<String> hashtags) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(yaml(title)).append("\n");
        sb.append("kind: ").append(kind).append("\n");
        if (digest != null && !digest.isBlank()) {
            sb.append("digest: ").append(yaml(digest)).append("\n");
        }
        if (!hashtags.isEmpty()) {
            sb.append("hashtags: [").append(String.join(", ",
                    hashtags.stream().map(LocalArchiveNodeExecutor::yaml).toList())).append("]\n");
        }
        sb.append("---\n\n");
        sb.append("# ").append(title).append("\n\n");
        if (digest != null && !digest.isBlank()) {
            sb.append("> ").append(digest).append("\n\n");
        }
        sb.append(body.trim()).append("\n");
        if (!hashtags.isEmpty()) {
            sb.append("\n").append(String.join(" ", hashtags)).append("\n");
        }
        return sb.toString();
    }

    private static String yaml(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    /** 上游输出定位：按某个特征字段找，找不到返回空 Map（所有字段读法都容错）。 */
    private static Map<String, Object> findUpstream(NodeInput input, String featureKey) {
        for (Object out : input.upstream().values()) {
            if (out instanceof Map<?, ?> map && map.containsKey(featureKey)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
        }
        return Map.of();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static List<String> list(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> items) {
            items.forEach(o -> out.add(String.valueOf(o)));
        }
        return out;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String toJson(Map<String, Object> m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL, "元数据序列化失败: " + e.getMessage());
        }
    }

    private static String prettyJson(Map<String, Object> m) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}

package com.agentflow.nodes.publish;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.support.HttpJson;
import com.agentflow.nodes.spi.ContentStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * xhs.draft — 小红书发布节点。
 *
 * 现状必须说清楚（不做假承诺）：小红书**没有**面向个人创作者的公开"存草稿"API，
 * 第三方逆向 Cookie 方案违反平台条款且随时风控失效，本项目不实现、不建议。
 * 因此本节点交付的是"发布就绪的图文包"：
 *   1) 按平台硬约束校验（标题 ≤20 字、正文 ≤1000 字、图片 ≤18 张）并把超限项写进 warnings（不失败，交人工微调）；
 *   2) 在内容库成品目录写 note.txt（可直接粘贴）+ xhs-package.json（图序 + 标签 + 封面文案）；
 *   3) 若配置了 endpoint（自建/官方开放平台代理，如企业号服务商接口），POST 该包并回报 published=true。
 *
 * params: endpoint(可选，留空=只出本地图文包) / title/body/hashtags/images(缺省取上游)
 *         credentialId(可选，接入开放平台时从凭证库取 token)
 * 输出: published(false|true) / packagePath / warnings[] / titleLen / bodyLen / imageCount
 *
 * 四件套口径：超时 30s（本地打包）/ 45s（外发）；noRetry（外发重试可能重复发帖）；
 * 外发失败 → 降级为"本地包已就绪"，degraded=true 显性，绝不静默丢内容。
 */
public class XhsDraftNodeExecutor implements NodeExecutor {

    public static final String TYPE = "xhs.draft";
    private static final int TITLE_MAX = 20;
    private static final int BODY_MAX = 1000;
    private static final int IMAGES_MAX = 18;

    private final ContentStore contentStore;
    private final HttpJson http = new HttpJson(Duration.ofSeconds(20));

    public XhsDraftNodeExecutor(ContentStore contentStore) {
        this.contentStore = contentStore;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(45);
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.noRetry();
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        if (contentStore == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "xhs.draft 未装配内容库（ContentStore 缺席）");
        }
        Map<String, Object> copy = findUpstream(input, "body");
        String title = firstNonBlank(input.paramAsString("title", null), str(copy.get("title")));
        String body = firstNonBlank(input.paramAsString("body", null), str(copy.get("body")));
        if (title.isBlank() || body.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "xhs.draft 缺少标题或正文：请传 title/body 参数，或把 copy.write(mode=xiaohongshu) 作为上游");
        }
        List<String> hashtags = stringList(firstNonNull(input.param("hashtags"), copy.get("hashtags")));
        List<Object> images = new ArrayList<>(stringKeyList(firstNonNull(input.param("images"), copy.get("images"))));

        List<String> warnings = new ArrayList<>();
        if (title.length() > TITLE_MAX) {
            warnings.add("标题 " + title.length() + " 字，超出小红书 " + TITLE_MAX + " 字建议，发布前请精简");
        }
        if (body.length() > BODY_MAX) {
            warnings.add("正文 " + body.length() + " 字，超出小红书 " + BODY_MAX + " 字上限，将被平台截断");
        }
        if (images.isEmpty()) {
            warnings.add("无配图：小红书图文笔记必须有图，请把 image.cover 接到上游");
        } else if (images.size() > IMAGES_MAX) {
            warnings.add("图片 " + images.size() + " 张，超出 " + IMAGES_MAX + " 张上限，仅前 " + IMAGES_MAX + " 张入包");
            images = images.subList(0, IMAGES_MAX);
        }
        if (hashtags.isEmpty()) {
            warnings.add("无标签，建议至少 5 个");
        }

        String noteText = buildNote(title, body, hashtags);
        String artifactKey = firstNonBlank(input.paramAsString("artifactId", null), "");
        long artifactId;
        if (!artifactKey.isBlank()) {
            artifactId = Long.parseLong(artifactKey.trim()); // 追加到已有成品（publish.local 先跑的场景）
        } else {
            artifactId = contentStore.insert(ctx.executionId(), "xhs-package", title, noteText,
                    "{\"mode\":\"xiaohongshu\",\"traceId\":\"" + ctx.traceId() + "\"}");
        }
        String packagePath = contentStore.writeText(artifactId, "note.txt", noteText);
        Map<String, Object> pkg = new HashMap<>();
        pkg.put("title", title);
        pkg.put("body", body);
        pkg.put("hashtags", hashtags);
        pkg.put("coverLines", copy.getOrDefault("coverLines", List.of()));
        pkg.put("images", images);
        pkg.put("artifactId", artifactId);
        contentStore.writeText(artifactId, "xhs-package.json", toJson(pkg));
        contentStore.attachStaged(ctx.executionId(), artifactId);

        Map<String, Object> data = new HashMap<>();
        data.put("published", false);
        data.put("channel", "xhs-local-package");
        data.put("artifactId", artifactId);
        data.put("packagePath", contentStore.artifactDir(artifactId));
        data.put("notePath", packagePath);
        data.put("warnings", warnings);
        data.put("titleLen", title.length());
        data.put("bodyLen", body.length());
        data.put("imageCount", images.size());

        String endpoint = input.paramAsString("endpoint", null);
        if (endpoint != null && !endpoint.isBlank()) {
            // 开放平台/自建代理口子：只有真配了地址才外发，响应体原样带出便于排查风控返回
            String resp = http.postJson(endpoint, toJson(pkg), headers(input));
            data.put("published", true);
            data.put("channel", "xhs-open-api");
            data.put("response", HttpJson.brief(resp));
        }
        return NodeOutput.ok(data);
    }

    @Override
    public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
        Map<String, Object> data = new HashMap<>();
        data.put("published", false);
        data.put("channel", "xhs-local-package");
        data.put("degradeNote", "外发失败，图文包已在本地内容库");
        return Optional.of(NodeOutput.degraded(data,
                "小红书外发失败，已保留本地图文包: " + safeMessage(lastError)));
    }

    /** 外发时按参数决定是否带 Bearer 头（开放平台 token 由编排层注入，本节点不落库不打印）。 */
    private static String[] headers(NodeInput input) {
        String token = input.paramAsString("accessToken", null);
        return token == null || token.isBlank() ? null : new String[]{"Authorization", "Bearer " + token};
    }

    private static String buildNote(String title, String body, List<String> hashtags) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n").append(body.trim()).append("\n");
        if (!hashtags.isEmpty()) {
            sb.append("\n").append(String.join(" ", hashtags)).append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findUpstream(NodeInput input, String featureKey) {
        for (Object out : input.upstream().values()) {
            if (out instanceof Map<?, ?> map && map.containsKey(featureKey)) {
                return (Map<String, Object>) map;
            }
        }
        return Map.of();
    }

    private static List<String> stringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> items) {
            items.forEach(o -> {
                String s = String.valueOf(o);
                if (!s.isBlank() && !"null".equals(s)) {
                    out.add(s);
                }
            });
        } else if (v instanceof String s && !s.isBlank()) {
            for (String p : s.split("[,，\\s]+")) {
                if (!p.isBlank()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** 图片列表：既接受 ["a.png"] 也接受 [{url,localPath}] 结构，包内统一保留原结构。 */
    private static List<Object> stringKeyList(Object v) {
        List<Object> out = new ArrayList<>();
        if (v instanceof List<?> items) {
            out.addAll(items);
        }
        return out;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
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
            return HttpJson.mapper().writeValueAsString(m);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL, "图文包序列化失败: " + e.getMessage());
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}

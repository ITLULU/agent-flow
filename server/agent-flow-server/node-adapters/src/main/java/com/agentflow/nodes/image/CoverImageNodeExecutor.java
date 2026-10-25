package com.agentflow.nodes.image;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.copy.PromptTemplates;
import com.agentflow.nodes.spi.ContentStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * image.cover — 主图/配图生成节点，支持场景自适应、风格预设、拼图与"不像 AI"质感控制。
 *
 * 新增能力（P4B 图片增强）：
 * - scene：xhs-cover xhs-body wechat-cover wechat-chapter，自动选比率与提示词骨架
 * - promptStyle：film street minimal warm dark vintage 六种预设风格词
 * - negativePrompt：英文反词，防塑料感/乱码/过度光滑（万相原生支持 negative_prompt）
 * - referenceStyle：用户给出参考图的风格描述，拼入正向提示词
 * - collage："2x2" 时生成 4 张并拼成四宫格（白边手机截屏风格），同时返回单张
 *
 * 向后兼容：不传 scene 时行为与旧版一致（ratio/kind 参数照旧生效），现有流程零影响。
 */
public class CoverImageNodeExecutor implements NodeExecutor {

    public static final String TYPE = "image.cover";
    private static final int MAX_N = 4;
    /** 一键场景映射：{标签, 默认比例, 提示词模板名} */
    private static final Map<String, ScenePreset> SCENES = Map.of(
            "xhs-cover", new ScenePreset("xhs-cover", "3:4", "image/xhs-cover.txt"),
            "xhs-body", new ScenePreset("xhs-body", "1:1", "image/xhs-body.txt"),
            "wechat-cover", new ScenePreset("wechat-cover", "2.35:1", "image/wechat-cover.txt"),
            "wechat-chapter", new ScenePreset("wechat-chapter", "4:3", "image/wechat-chapter.txt"));

    /** 典型"不像 AI"风格词库，用户传 key 即注入对应英文描述。 */
    private static final Map<String, String> STYLES = Map.of(
            "film", "analog film photo, portra 400 color, light leak, soft grain, nostalgic warmth",
            "street", "street photography, snapshot aesthetic, harsh natural light, urban texture, candid moment",
            "minimal", "minimalist composition, negative space, clean lines, muted palette, zen calm",
            "warm", "golden hour light, cozy atmosphere, warm tones, soft shadows, hygge feeling",
            "dark", "low key lighting, moody atmosphere, deep shadows, cinematic noir, single light source",
            "vintage", "vintage magazine editorial, faded colors, paper texture, retro 90s, slightly desaturated"
    );

    /** 默认负向提示词：万相 v2.1 的 negative_prompt 字段用，防 AI 味。 */
    private static final String DEFAULT_NEGATIVE = "plastic skin, waxy face, AI smoothness, perfect symmetry, "
            + "studio lighting, uncanny valley, text watermark, Chinese characters, signature, "
            + "extra fingers, bad anatomy, fused body parts, cloned face, low quality, blurry";

    private final DashScopeImageClient client;
    private final ContentStore contentStore;

    public CoverImageNodeExecutor(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null);
    }

    public CoverImageNodeExecutor(String apiKey, String baseUrl, ContentStore contentStore) {
        this.client = new DashScopeImageClient(apiKey, baseUrl);
        this.contentStore = contentStore;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(180);
    }

    @Override
    public boolean hasSideEffects() {
        return true;
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.noRetry();
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        String scene = input.paramAsString("scene", null);
        ScenePreset preset = scene != null ? SCENES.getOrDefault(scene, null) : null;

        String prompt = resolvePrompt(input, preset);
        if (prompt.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "image.cover 无法确定提示词：prompt/topic 参数为空且上游没有标题类输出");
        }
        String negativePrompt = input.paramAsString("negativePrompt", DEFAULT_NEGATIVE);

        String kind = preset != null ? preset.kind : input.paramAsString("kind", "cover");
        long pollIntervalMs = asLong(input.param("pollIntervalMs"), 2000L);
        long maxWaitMs = asLong(input.param("maxWaitMs"), 90_000L);
        boolean save = asBool(input.param("save"), true);
        String model = input.paramAsString("model", "wanx2.1-t2i-turbo");

        String sizeOverride = input.paramAsString("size", null);
        RatioSpec spec;
        if (sizeOverride != null && !sizeOverride.isBlank()) {
            spec = RatioSpec.resolve(sizeOverride);
        } else if (preset != null) {
            spec = RatioSpec.resolve(preset.ratio);
        } else {
            spec = RatioSpec.resolve(input.paramAsString("ratio", null));
        }

        String collage = input.paramAsString("collage", null);
        int n = "2x2".equals(collage) ? 4 : clampN(asInt(input.param("n"), 1));

        long started = System.currentTimeMillis();
        String taskId = client.submit(model, prompt, spec.size(), n, negativePrompt);
        List<String> urls = client.awaitUrls(taskId, pollIntervalMs, maxWaitMs);

        boolean wantCollage = "2x2".equals(collage);
        boolean store = save && contentStore != null;
        List<byte[]> tiles = new ArrayList<>();
        List<Map<String, Object>> images = new ArrayList<>();
        int idx = 0;
        for (String url : urls) {
            idx++;
            Map<String, Object> img = new HashMap<>();
            img.put("url", url);
            img.put("kind", kind);
            img.put("ratio", spec.ratio());
            img.put("size", spec.size());
            img.put("width", spec.width());
            img.put("height", spec.height());
            if (store || wantCollage) {
                byte[] bytes = client.download(url); // 一张只下一次：暂存与拼图复用同一份字节
                tiles.add(bytes);
                if (store) {
                    String fileName = kind + "-" + idx + "-" + spec.ratio().replace(":", "x") + ".png";
                    img.put("fileName", fileName);
                    img.put("localPath", contentStore.stage(ctx.executionId(), fileName, bytes));
                    img.put("bytes", bytes.length);
                }
            }
            images.add(img);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("images", images);
        data.put("count", images.size());
        data.put("ratio", spec.ratio());
        data.put("size", spec.size());
        data.put("model", model);
        data.put("taskId", taskId);
        data.put("prompt", prompt);
        data.put("negativePrompt", negativePrompt);
        data.put("stored", store);
        data.put("costMs", System.currentTimeMillis() - started);
        if (wantCollage) {
            stitchCollage(tiles, spec, store, ctx, data);
        }
        return NodeOutput.ok(data);
    }

    /**
     * 四宫格拼接。失败不阻断单张交付，但**必须显性写回 collageError**——
     * 静默 catch 会让调用方以为"这次本来就不该有拼图"（skill §3：禁止静默降级）。
     */
    private void stitchCollage(List<byte[]> tiles, RatioSpec spec, boolean store, ExecutionContext ctx,
                               Map<String, Object> data) {
        if (tiles.size() != 4) {
            data.put("collageError", "四宫格需要 4 张成图，实际只拿到 " + tiles.size()
                    + " 张（万相任务返回数量可能少于请求的 n）");
            return;
        }
        try {
            byte[] collageBytes = ImageStitcher.stitch2x2(tiles);
            Map<String, Object> collageMap = new HashMap<>();
            collageMap.put("ratio", spec.ratio());
            collageMap.put("format", "2x2");
            collageMap.put("bytes", collageBytes.length);
            if (store) {
                String fn = "collage-2x2-" + spec.ratio().replace(":", "x") + ".png";
                collageMap.put("localPath", contentStore.stage(ctx.executionId(), fn, collageBytes));
            }
            data.put("collage", collageMap);
        } catch (Exception e) {
            data.put("collageError", "四宫格拼接失败（单张仍可用）: " + safeMessage(e));
        }
    }

    @Override
    public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
        Map<String, Object> data = new HashMap<>();
        data.put("images", List.of());
        data.put("count", 0);
        data.put("stored", false);
        return Optional.of(NodeOutput.degraded(data, "生图失败，按无图发布: " + safeMessage(lastError)));
    }

    /**
     * 提示词配方。主体 = 显式 prompt > topic > 上游标题（创作流水线里 copy.write 先出标题，再自动配图）。
     *
     * 两条路径（scene 决定走哪条）：
     * - 有 scene：主体 + 参考图风格配方 + 场景骨架模板 + 画风词——这是"换内容、留风格"的主路径；
     * - 无 scene：保持既有的中文骨架（为内容《X》设计封面主图/内文配图…），已在文档与单测里承诺。
     *
     * style 与 promptStyle 是两个不同层级的东西，都生效：
     * style 是用户自己写的风格描述（自由文本，中英文都行），promptStyle 是本节点的预设画风 key。
     */
    private String resolvePrompt(NodeInput input, ScenePreset preset) {
        String explicit = input.paramAsString("prompt", null);
        String topic = input.paramAsString("topic", null);
        String title = upstreamString(input, "title");
        String base = firstNonBlank(explicit, topic, title);
        if (base.isBlank()) {
            return "";
        }
        String referenceStyle = input.paramAsString("referenceStyle", null);
        String freeStyle = input.paramAsString("style", null);
        String presetStyle = styleWords(input.paramAsString("promptStyle", null));

        if (preset != null) {
            StringBuilder sb = new StringBuilder(base);
            appendIf(sb, referenceStyle);
            sb.append(". ").append(PromptTemplates.load(preset.template).trim());
            appendIf(sb, freeStyle);
            appendIf(sb, presetStyle);
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("为内容《").append(base).append("》设计")
                .append("cover".equals(input.paramAsString("kind", "cover")) ? "封面主图" : "内文配图")
                .append("：现代简体中文社媒视觉风格，构图简洁有张力，色块分明，无文字水印");
        appendIf(sb, referenceStyle);
        appendIf(sb, freeStyle);
        appendIf(sb, presetStyle);
        return sb.toString();
    }

    private static void appendIf(StringBuilder sb, String extra) {
        if (extra != null && !extra.isBlank()) {
            String tail = extra.trim();
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '，' && !tail.startsWith("，")) {
                sb.append("，");
            }
            sb.append(tail);
        }
    }

    private static String styleWords(String key) {
        return key == null ? null : STYLES.get(key);
    }

    private static String upstreamString(NodeInput input, String key) {
        for (Object out : input.upstream().values()) {
            if (out instanceof Map<?, ?> map) {
                Object v = map.get(key);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v).trim();
                }
            }
        }
        return "";
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static int clampN(int n) {
        if (n <= 0) {
            return 1;
        }
        return Math.min(n, MAX_N);
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

    private static boolean asBool(Object v, boolean def) {
        if (v == null) {
            return def;
        }
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }

    record ScenePreset(String kind, String ratio, String template) {
    }
}
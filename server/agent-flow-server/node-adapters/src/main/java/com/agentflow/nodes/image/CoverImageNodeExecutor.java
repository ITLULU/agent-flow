package com.agentflow.nodes.image;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.spi.ContentStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * image.cover — 主图/配图生成节点（比例可控 + 落地保存）。
 *
 * 与 image.wanxiang 的差异（为什么要多一个节点而不是改老节点）：
 * image.wanxiang 只回一个 24 小时过期的远端 URL，尺寸固定 1024*1024、n 固定 1，
 * 而创作闭环要的是"按渠道比例出图 + 图是真的存下来了"。老节点的契约由单测钉住、
 * 已在文档里对用户承诺，因此新增本节点承载创作语义，两者并存。
 *
 * params: prompt(可缺省，缺省时由 topic / 上游标题拼提示词)
 *         ratio(默认 3:4；支持 xhs / square / portrait / landscape / story / banner /
 *              wechat-share / wechat-small 及 "9:16"、"1024*1024" 写法)
 *         size(可选，显式覆盖 ratio 推导) / n(默认 1，1~4)
 *         kind(cover=主图 | illustration=配图，仅影响文件名前缀与元数据)
 *         model(默认 wanx2.1-t2i-turbo) / pollIntervalMs(默认 2000) / maxWaitMs(默认 90000)
 *         save(默认 true：把远端图下载进内容库暂存区；false 只回 URL)
 * 输出: images[{url,localPath,fileName,bytes,ratio,size}] / count / ratio / size / model / taskId / costMs / stored
 *
 * 四件套口径：
 * - 超时：180s（n≤4 张 + 逐张下载的总窗口）；maxWaitMs 只封"任务等待"，不另立连接超时口径。
 * - 重试：noRetry——引擎重试=二次提交生图任务=重复计费，与 image.wanxiang 同一口径（单测钉死）。
 * - 异常：连接拒绝 C0001 / 超时 C0002 / 协议与任务失败 C0003；比例或尺寸非法 A0400（不可重试）。
 * - 降级：输出空 images 续跑（无主图也能发纯文字稿），degraded=true 显性。
 */
public class CoverImageNodeExecutor implements NodeExecutor {

    public static final String TYPE = "image.cover";
    private static final int MAX_N = 4;

    private final DashScopeImageClient client;
    private final ContentStore contentStore; // 可为 null：未配置内容库时只回远端 URL

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
    public RetryPolicy retryPolicy() {
        return RetryPolicy.noRetry(); // 防重复计费，勿改
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        String prompt = resolvePrompt(input);
        if (prompt.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "image.cover 无法确定提示词：prompt/topic 参数为空且上游没有标题类输出");
        }
        String kind = input.paramAsString("kind", "cover");
        int n = clampN(asInt(input.param("n"), 1));
        long pollIntervalMs = asLong(input.param("pollIntervalMs"), 2000L);
        long maxWaitMs = asLong(input.param("maxWaitMs"), 90_000L);
        boolean save = asBool(input.param("save"), true);
        String model = input.paramAsString("model", "wanx2.1-t2i-turbo");

        String sizeOverride = input.paramAsString("size", null);
        RatioSpec spec = sizeOverride != null && !sizeOverride.isBlank()
                ? RatioSpec.resolve(sizeOverride)          // 显式尺寸：仍走范围校验
                : RatioSpec.resolve(input.paramAsString("ratio", null));

        long started = System.currentTimeMillis();
        String taskId = client.submit(model, prompt, spec.size(), n);
        List<String> urls = client.awaitUrls(taskId, pollIntervalMs, maxWaitMs);

        List<Map<String, Object>> images = new ArrayList<>();
        int idx = 0;
        for (String url : urls) {
            idx++;
            Map<String, Object> img = new HashMap<>();
            img.put("url", url);
            img.put("ratio", spec.ratio());
            img.put("size", spec.size());
            img.put("width", spec.width());
            img.put("height", spec.height());
            if (save && contentStore != null) {
                String fileName = kind + "-" + idx + "-" + spec.ratio().replace(":", "x") + ".png";
                byte[] bytes = client.download(url);
                String staged = contentStore.stage(ctx.executionId(), fileName, bytes);
                img.put("fileName", fileName);
                img.put("localPath", staged);
                img.put("bytes", bytes.length);
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
        data.put("stored", save && contentStore != null);
        data.put("costMs", System.currentTimeMillis() - started);
        return NodeOutput.ok(data);
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
     * 提示词优先级：显式 prompt > prompt 拼接（topic/风格/渠道比例语义）> 上游成稿标题。
     * 创作流水线里通常让 copy.write 先出标题，再由本节点自动配图，所以这个回退链是主要路径。
     */
    private String resolvePrompt(NodeInput input) {
        String explicit = input.paramAsString("prompt", null);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        StringBuilder sb = new StringBuilder();
        String topic = input.paramAsString("topic", null);
        String style = input.paramAsString("style", null);
        String title = upstreamString(input, "title");
        String base = firstNonBlank(topic, title);
        if (base.isBlank()) {
            return "";
        }
        sb.append("为内容《").append(base).append("》设计")
                .append("cover".equals(input.paramAsString("kind", "cover")) ? "封面主图" : "内文配图")
                .append("：现代简体中文社媒视觉风格，构图简洁有张力，色块分明，无文字水印");
        if (style != null && !style.isBlank()) {
            sb.append("，风格：").append(style.trim());
        }
        return sb.toString();
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
}

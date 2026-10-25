package com.agentflow.nodes.copy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.llm.LlmGateway;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * copy.write — 创作节点（一条链路同时支撑两种交付物：公众号长文 / 小红书笔记）。
 *
 * params: mode(wechat|xiaohongshu，默认 wechat) / topic(必填或从上游热点取)
 *         material(可选素材文本；缺省时自动汇总上游 hotspot.fetch / article.extract 输出)
 *         distill(默认 true：素材 ≥800 字时先花一次调用蒸成要点清单再创作，杜绝整句照抄；false=直接用原素材)
 *         audience(目标读者) / tone(语气，如"克制、专业、有画面感")
 *         length(目标字数；缺省时取画布设置 wechatLength/xhsLength，再缺省兜底 wechat 2000、xhs 1000)
 *         model(覆盖默认模型) / temperature(默认 0.8，创作需要发散)
 * 输出: title / subtitle / digest / body / hashtags[] / keyPoints[] / coverLines[] / wordCount /
 *       targetWordCount / lengthNote(字数远不达标时才有) /
 *       materialDistilled / mode / model / totalTokens / parseFallback
 *
 * 四件套口径：
 * - 超时：120s（长文一次生成 1500 字，90s 的 llm.chat 口径不够用，这里按输出规模放宽）。
 * - 重试：1 次、2s 退避。按 token 计费的节点，重试次数必须最小。
 * - 异常：网关缺席 → A0400 配置错（不可重试）；SDK/上游异常由网关转译为 C0003/C0002/C0001。
 * - 降级：**不降级**（与 wechat.draft 同一判断）——半截文案既不能发也不可读，失败必须显性 FAILED，
 *   由编排层决定是否改跑纯采集链路。这是本节点与 llm.chat（可降级）唯一的语义差别。
 */
public class CopyWriteNodeExecutor implements NodeExecutor {

    public static final String TYPE = "copy.write";
    public static final String MODE_WECHAT = "wechat";
    public static final String MODE_XHS = "xiaohongshu";
    /** 画布设置（DSL 根 settings）里的字数组键：生成字数在画布配置时就定，节点参数只做单点覆盖。 */
    public static final String SETTING_WECHAT_LENGTH = "wechatLength";
    public static final String SETTING_XHS_LENGTH = "xhsLength";
    static final int DEFAULT_WECHAT_LENGTH = 2000;
    static final int DEFAULT_XHS_LENGTH = 1000;
    private static final int MAX_MATERIAL_CHARS = 12000;
    /** 素材超过这个长度就先蒸馏成要点：够短就不可能成段照抄，也就不必多花一次调用。 */
    private static final int DISTILL_MIN_CHARS = 800;
    /** 兜底取"最长一节"时的最小字数门槛：低于它就不敢认定那是正文，宁可整体交付。 */
    private static final int MIN_BODY_CHARS = 200;

    private final Supplier<LlmGateway> gatewaySupplier;

    public CopyWriteNodeExecutor(Supplier<LlmGateway> gatewaySupplier) {
        this.gatewaySupplier = gatewaySupplier;
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
        return new RetryPolicy(1, 2000L);
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        LlmGateway gateway = gatewaySupplier == null ? null : gatewaySupplier.get();
        if (gateway == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "LLM 网关未配置（缺少 BAILIAN_API_KEY），copy.write 无法执行");
        }
        String mode = normalizeMode(input.paramAsString("mode", MODE_WECHAT));
        String topic = firstNonBlank(input.paramAsString("topic", null), upstreamTopic(input));
        if (topic.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "copy.write 缺少主题：请设置 topic 参数，或让 hotspot.fetch / article.extract 作为上游");
        }
        String material = resolveMaterial(input);
        int length = resolveLength(input, ctx, mode);
        // 反抄袭前置：素材给得越整，模型越容易贴着原句改写（实测整篇原文喂进去，出来的稿子会复述原文小标题和例句）。
        // 先蒸成"要点清单"，让写作这一步在物理上无整句可抄；蒸馏失败就抛，不悄悄退回原文素材继续写。
        boolean materialDistilled = false;
        if (shouldDistill(input, material)) {
            String digest = digestMaterial(gateway, input, topic, material);
            material = trimMaterial(digest);
            materialDistilled = true;
        }

        String system = PromptTemplates.load(MODE_WECHAT.equals(mode) ? "copy-wechat.txt" : "copy-xiaohongshu.txt");
        String prompt = buildUserPrompt(mode, topic, material, input, length);
        Double temperature = asDouble(input.param("temperature"), 0.8d);

        LlmGateway.Reply reply;
        try {
            reply = gateway.chat(new LlmGateway.Request(input.paramAsString("model", null), system, prompt, temperature));
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "LLM 网关异常: " + e.getMessage());
        }
        if (reply == null || reply.content() == null || reply.content().isBlank()) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "模型返回空内容，无成品可交付");
        }

        Map<String, List<String>> sections = parseSections(reply.content());
        Map<String, Object> data = new HashMap<>();
        String body = firstPresent(sections, "正文", "封面文案");
        // 兜底口径按"正文拿没拿到"判，不按"有没有看到节名"判：模型把节名写歪（【全文】/漏写【正文】）时，
        // 前者仍会静默交付空 body，而空 body 就是这条链路最坏的产出——下游要么无稿可归档，要么发一篇白稿进草稿箱。
        boolean parseFallback = body.isBlank();
        boolean longestSectionUsed = false;
        if (parseFallback) {
            // 漏写【正文】时，正文实际落在它后面那一节里（实测落进【金句】）。回复里最长的一节就是稿子本体
            // （标题/摘要/标签都短），取它比"整段回复当正文"干净——后者会把【标题】【摘要】一起塞进公众号正文。
            String longest = sections.values().stream()
                    .map(CopyWriteNodeExecutor::join)
                    .filter(v -> v.length() >= MIN_BODY_CHARS)
                    .max(java.util.Comparator.comparingInt(String::length))
                    .orElse("");
            longestSectionUsed = !longest.isEmpty();
            body = longestSectionUsed ? longest : reply.content().trim();
        }
        data.put("mode", mode);
        data.put("title", join(sections.get("标题")));
        data.put("subtitle", join(sections.get("副标题")));
        data.put("digest", join(sections.get("摘要")));
        data.put("body", body);
        data.put("hashtags", listField(sections.get("标签"), true));
        data.put("keyPoints", listField(sections.get("金句"), false));
        data.put("coverLines", listField(sections.get("封面文案"), false));
        int actualWords = countWords(body);
        data.put("wordCount", actualWords);
        data.put("targetWordCount", length);
        // 字数是"参考+夹取"，不是硬截断：模型给短了不判失败，但要把差距写进输出，
        // 否则画布上设的 2000 字只活在提示词里，时间线上看不出到底写没写够。
        if (actualWords < length / 2) {
            data.put("lengthNote", "正文实际 " + actualWords + " 字，不到本次要求 " + length
                    + " 字的一半：模型没写够，可补素材或重跑该节点（不自动截断/补写）");
        }
        data.put("materialDistilled", materialDistilled);
        data.put("parseFallback", parseFallback);
        data.put("model", reply.modelUsed() == null ? "default" : reply.modelUsed());
        if (reply.totalTokens() != null) {
            data.put("totalTokens", reply.totalTokens());
        }
        if (parseFallback) {
            data.put("parseNote", longestSectionUsed
                    ? "回复里没有【正文】这一节，已取其中最长的节作为正文（标题/摘要类短节已排除），请核对排版与节名"
                    : "模型未按【标记】分节，已把整段回复作为正文交付（标题留空，需人工补）");
        }
        return NodeOutput.ok(data);
    }

    /**
     * 字数优先级：节点显式 length > 画布设置（wechatLength/xhsLength）> 内置默认（wechat 2000、xhs 1000）。
     * 画布设置是"生成时应该限制的字数"，节点参数只是单点覆盖；两种来源都过一遍 MIN/MAX 夹取，
     * 防止手滑填 0 或十万把链路变成烧 token 的复读机。
     * 上限按渠道分档：小红书正文 1000 字是平台硬限（xhs.draft 超了会警告"将被平台截断"），
     * 画布上把 xhsLength 调到 3000 也只会产出一篇发不全的稿子，所以直接夹到 1000。
     */
    private static final int MIN_LENGTH = 100;
    private static final int MAX_LENGTH = 10_000;
    static final int MAX_XHS_LENGTH = 1000;

    static int resolveLength(NodeInput input, ExecutionContext ctx, String mode) {
        boolean wechat = MODE_WECHAT.equals(mode);
        int fallback = wechat ? DEFAULT_WECHAT_LENGTH : DEFAULT_XHS_LENGTH;
        int max = wechat ? MAX_LENGTH : MAX_XHS_LENGTH;
        Object explicit = input.param("length");
        if (explicit != null) {
            int parsed = asInt(explicit, -1);
            if (parsed > 0) {
                return clampLength(parsed, max);
            }
        }
        Object setting = ctx.setting(wechat ? SETTING_WECHAT_LENGTH : SETTING_XHS_LENGTH);
        if (setting != null) {
            int parsed = asInt(setting, -1);
            if (parsed > 0) {
                return clampLength(parsed, max);
            }
        }
        return fallback;
    }

    private static int clampLength(int v, int max) {
        return Math.max(MIN_LENGTH, Math.min(v, max));
    }

    private static String buildUserPrompt(String mode, String topic, String material, NodeInput input, int length) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本次创作任务】\n");
        sb.append("渠道：").append(MODE_WECHAT.equals(mode) ? "微信公众号长文" : "小红书图文笔记").append("\n");
        sb.append("主题：").append(topic).append("\n");
        appendIfPresent(sb, "目标读者", input.paramAsString("audience", null));
        appendIfPresent(sb, "语气风格", input.paramAsString("tone", null));
        appendIfPresent(sb, "补充要求", input.paramAsString("extra", null));
        sb.append("篇幅：正文约 ").append(length).append(" 字\n");
        if (!material.isBlank()) {
            sb.append("\n【素材】（只作事实与观点参考，必须原创重写）\n").append(material);
        } else {
            sb.append("\n【素材】无外部素材，请基于主题自行组织观点，但不得编造具体数据与人名事件细节。");
        }
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String label, String v) {
        if (v != null && !v.isBlank()) {
            sb.append(label).append("：").append(v.trim()).append("\n");
        }
    }

    /** 短素材（热榜标题串）没有可抄的句子，不值得多花一次调用；长素材一律先蒸馏。distill=false 显式关闭。 */
    private static boolean shouldDistill(NodeInput input, String material) {
        return asBoolean(input.param("distill"), true) && material.length() >= DISTILL_MIN_CHARS;
    }

    private static String digestMaterial(LlmGateway gateway, NodeInput input, String topic, String material) {
        String user = "【写作主题】" + topic + "\n【待蒸馏素材】\n" + material;
        LlmGateway.Reply reply;
        try {
            // 蒸馏要收敛不要发散：0.2 远低于创作默认的 0.8，temperature 参数不该污染这一步
            reply = gateway.chat(new LlmGateway.Request(input.paramAsString("model", null),
                    PromptTemplates.load("material-digest.txt"), user, 0.2d));
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "素材蒸馏失败: " + e.getMessage());
        }
        if (reply == null || reply.content() == null || reply.content().isBlank()) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR,
                    "素材蒸馏返回空内容：不拿原文直接创作（原创性无法保证），请重试或显式传 distill=false");
        }
        return reply.content().trim();
    }

    /** 素材优先级：显式 material > 上游 article.extract 正文 > 上游 hotspot.fetch 条目。 */
    private static String resolveMaterial(NodeInput input) {
        String explicit = input.paramAsString("material", null);
        if (explicit != null && !explicit.isBlank()) {
            return trimMaterial(explicit);
        }
        for (Object out : input.upstream().values()) {
            if (out instanceof Map<?, ?> map && map.get("text") != null
                    && !String.valueOf(map.get("text")).isBlank()) {
                return trimMaterial(String.valueOf(map.get("text")));
            }
        }
        List<String> titles = new ArrayList<>();
        for (Object out : input.upstream().values()) {
            if (out instanceof Map<?, ?> map && map.get("items") instanceof List<?> items) {
                for (Object o : items) {
                    if (o instanceof Map<?, ?> m && m.get("title") != null) {
                        Object source = m.get("source");
                        titles.add("- " + m.get("title") + "（来源:" + (source == null ? "" : source) + "）");
                    }
                }
            }
        }
        return titles.isEmpty() ? "" : trimMaterial(String.join("\n", titles));
    }

    /** 上游热点标题当主题用：闭环链路里"抓热点→写稿"不应强制用户再手输一遍 topic。 */
    private static String upstreamTopic(NodeInput input) {
        for (Object out : input.upstream().values()) {
            if (out instanceof Map<?, ?> map) {
                Object t = map.get("title");
                if (t != null && !String.valueOf(t).isBlank()) {
                    return String.valueOf(t).trim();
                }
                if (map.get("items") instanceof List<?> items && !items.isEmpty()
                        && items.get(0) instanceof Map<?, ?> first && first.get("title") != null) {
                    return String.valueOf(first.get("title")).trim();
                }
            }
        }
        return "";
    }

    private static String trimMaterial(String s) {
        String t = s.trim();
        return t.length() > MAX_MATERIAL_CHARS ? t.substring(0, MAX_MATERIAL_CHARS) + "...[素材截断]" : t;
    }

    /** 按 【标记】 切节；标记行必须独占一行，节内保留原始换行。 */
    static Map<String, List<String>> parseSections(String text) {
        Map<String, List<String>> sections = new java.util.LinkedHashMap<>();
        String current = null;
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*【(.+?)】\\s*(.*)$").matcher(line);
            if (m.matches() && SECTION_MARKERS.contains(m.group(1).trim())) {
                closeSection(sections, current, buf);
                current = m.group(1).trim();
                buf = new StringBuilder(m.group(2));
            } else if (current != null) {
                buf.append("\n").append(line);
            }
        }
        closeSection(sections, current, buf);
        return sections;
    }

    /**
     * 只有模板约定的节名才算分节标记。模型爱在正文里用【小标题】排版，若把它们也当标记，
     * 【正文】这一节会在第一个小标题处被切断、后续内容全落到伪节里，节点便返回"SUCCESS 但 body 为空"
     * （2026-10-25 17:29 外链改写链路实跑复现，下游 publish.local 直接无正文可归档）。
     */
    private static final java.util.Set<String> SECTION_MARKERS =
            java.util.Set.of("标题", "副标题", "摘要", "正文", "标签", "金句", "封面文案");

    /** 收尾一节：空内容不登记，避免留下"节存在但值为空串"这种既骗过 containsKey 又交付不了东西的中间态。 */
    private static void closeSection(Map<String, List<String>> sections, String current, StringBuilder buf) {
        if (current == null) {
            return;
        }
        String value = buf.toString().trim();
        if (!value.isEmpty()) {
            sections.computeIfAbsent(current, k -> new ArrayList<>()).add(value);
        }
    }

    private static String firstPresent(Map<String, List<String>> sections, String... keys) {
        for (String k : keys) {
            String v = join(sections.get(k));
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String join(List<String> vals) {
        return vals == null || vals.isEmpty() ? "" : String.join("\n", vals).trim();
    }

    private static List<String> listField(List<String> raw, boolean splitBySpace) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        String joined = String.join(" ", raw).trim();
        if (joined.isEmpty()) {
            return out;
        }
        String[] parts = splitBySpace ? joined.split("[\\s,，]+") : joined.split("[|\n｜]+");
        for (String p : parts) {
            String v = p.trim();
            if (!v.isEmpty() && !"|".equals(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static String normalizeMode(String mode) {
        String m = mode == null ? MODE_WECHAT : mode.trim().toLowerCase();
        return switch (m) {
            case "wechat", "gzh", "公众号", "mp" -> MODE_WECHAT;
            case "xiaohongshu", "xhs", "小红书", "note" -> MODE_XHS;
            default -> throw new ApiException(ErrorCode.BAD_REQUEST,
                    "copy.write 不支持的 mode: " + mode + "（可选 wechat / xiaohongshu）");
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b == null ? "" : b.trim();
    }

    private static int countWords(String text) {
        int cjk = 0;
        int other = 0;
        boolean inWord = false;
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
                inWord = false;
            } else if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    other++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return cjk + other;
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

    /** DSL 里布尔既可能是 JSON true，也可能是画布传来的 "false" 字符串，两种都要认。 */
    private static boolean asBoolean(Object v, boolean def) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("false") || t.equals("0") || t.equals("off") || t.equals("no")) {
                return false;
            }
            if (t.equals("true") || t.equals("1") || t.equals("on") || t.equals("yes")) {
                return true;
            }
        }
        return def;
    }

    private static Double asDouble(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}

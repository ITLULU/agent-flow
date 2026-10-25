package com.agentflow.nodes.copy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.llm.LlmGateway;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * copy.originality — 原创度校验：把生成稿与原始参考文章对分，给一个可复现的相似度比，防抄袭。
 *
 * 为什么要两条腿：
 * - LLM 四维评分（措辞/结构/表达/观点，0~100，low≤25<medium≤50<high）沿用用户既有 tool-site-backend 的
 *   ArticleSimilarityAnalyzer 口径（含 articleA=原文、articleB=生成文的顺序约定），它看得出"换词照抄"这种语义级抄袭；
 * - 本地 5-gram 覆盖度 + 最长连续公共片段是可复现的客观下限：模型分数会飘，"连续 24 字一模一样"不会。
 *   两者取更严的那个当结论，任何一侧漏判都不会放行。
 *
 * params: generated(缺省取上游创作产物的 body) / source(缺省取上游 article.extract|http.collect 的 text)
 *         maxScore(默认 25，超过即判风险) / strict(默认 false) / model / useLlm(默认 true)
 * 输出: similarityScore / riskLevel / maxScore / localOverlap / maxCommonRun / commonFragments[] /
 *       method(llm+local|local-only) / llmNote(仅模型评分缺失时) / dimensions{} / summary
 *       / sourceChars / generatedChars
 *
 * 四件套口径：
 * - 超时 90s（一次评分调用）；重试 1 次、2s 退避（按 token 计费，尽量少重）。
 * - 异常：两份文本缺任一份 → A0400（无对照物的"通过"是假绿）；LLM 缺席或报错 → 退化为 local-only 并写明，
 *   校验本身不阻断链路。
 * - 降级：两种情形都必须显性，不许"绿着放行"——
 *   ① 超阈值即 degraded（整条执行变 SUCCESS_WITH_DEGRADED，稿子照样归档，但你在时间线看得见它有风险）；
 *   ② 请求了模型评分（useLlm=true）却没拿到（网关缺席/调用异常/报文不是 JSON）也 degraded，
 *      reason 写清是哪一种，输出里带 `llmNote`。理由：本地 5-gram 只看得出整句照抄，看不出
 *      "换了词但沿用骨架"，实测同一篇稿本地 0 分、模型 68 分——模型缺位时的 0 分是假绿（评审 2026-10-25 执行 16）。
 *   strict=true 时超阈值改为抛错让链路停在发布前——默认不阻断，因为拦截失败看起来像链路坏了，容易被当成 bug 绕过去。
 */
public class OriginalityCheckNodeExecutor implements NodeExecutor {

    public static final String TYPE = "copy.originality";
    static final int RISK_LOW_MAX = 25;
    static final int RISK_MEDIUM_MAX = 50;
    private static final int GRAM = 5;
    /** 连续这么多字一模一样就是照抄，模型打几分都不算过。 */
    private static final int HARD_COPY_RUN_CHARS = 24;
    private static final int MAX_SOURCE_CHARS = 12000;
    private static final int MAX_GENERATED_CHARS = 8000;
    private static final int DEFAULT_MAX_SCORE = 25;
    private static final int MAX_FRAGMENTS = 5;

    private final Supplier<LlmGateway> gatewaySupplier;
    /** 模型常在 JSON 字符串值里直接放真实换行，严格模式会解析失败——参考项目踩过同一个坑，这里放宽。 */
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    public OriginalityCheckNodeExecutor(Supplier<LlmGateway> gatewaySupplier) {
        this.gatewaySupplier = gatewaySupplier;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(90);
    }

    @Override
    public RetryPolicy retryPolicy() {
        return new RetryPolicy(1, 2000L);
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        String generated = firstNonBlank(input.paramAsString("generated", null), upstreamGenerated(input));
        String source = firstNonBlank(input.paramAsString("source", null), upstreamSourceText(input));
        if (generated.isBlank() || source.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "copy.originality 需要两份文本对照：生成稿" + (generated.isBlank() ? "缺失（把 copy.write 作为上游或传 generated）" : "已取到")
                            + "，原始文章" + (source.isBlank() ? "缺失（把 article.extract / http.collect 作为上游或传 source）" : "已取到"));
        }
        int maxScore = asInt(input.param("maxScore"), DEFAULT_MAX_SCORE);
        boolean strict = asBoolean(input.param("strict"), false);
        boolean useLlm = asBoolean(input.param("useLlm"), true);

        LocalMatch local = LocalMatch.of(source, generated);
        int localScore = (int) Math.round(local.containment() * 100);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("localOverlap", round2(local.containment()));
        data.put("maxCommonRun", local.maxRun());
        data.put("commonFragments", local.runs());
        data.put("sourceChars", source.length());
        data.put("generatedChars", generated.length());

        int llmScore = -1;
        String method = "local-only";
        String llmUnavailable = null;
        if (useLlm) {
            ModelScore model = askModel(input, source, generated);
            JsonNode llm = model.json();
            if (llm == null || !llm.path("similarityScore").isNumber()) {
                llmUnavailable = llm == null ? model.unavailable() : "模型报文里没有 similarityScore 数字";
            } else {
                llmScore = llm.path("similarityScore").asInt();
                method = "llm+local";
                data.put("summary", llm.path("summary").asText(""));
                data.put("dimensions", dimensions(llm));
                data.put("modelFragments", modelFragments(llm));
            }
        }
        if (llmUnavailable != null) {
            // 要了模型评分却没拿到，原因必须留在输出里：否则一次网关故障就把"未校验"伪装成"0 分通过"
            data.put("llmNote", llmUnavailable);
        }
        int score = Math.max(localScore, llmScore);
        if (local.maxRun() >= HARD_COPY_RUN_CHARS) {
            score = Math.max(score, 60); // 硬抄袭证据：至少中偏高风险，不能被模型的"整体改写"说法压低
        }
        score = Math.max(0, Math.min(100, score));
        String risk = riskLevel(score);
        data.put("method", method);
        data.put("similarityScore", score);
        data.put("riskLevel", risk);
        data.put("maxScore", maxScore);
        if (llmScore >= 0) {
            data.put("llmScore", llmScore);
        }
        data.put("localScore", localScore);

        boolean overThreshold = score > maxScore || local.maxRun() >= HARD_COPY_RUN_CHARS;
        if (!overThreshold && llmUnavailable == null) {
            return NodeOutput.ok(data);
        }
        String reason = "原创度不达标：相似度 " + score + "/100（阈值 " + maxScore
                + "），最长连续相同 " + local.maxRun() + " 字"
                + (local.maxRun() >= HARD_COPY_RUN_CHARS ? "——已达照抄级别，必须人工改写" : "，建议复核措辞与结构");
        if (!overThreshold) {
            reason = "本地口径过了，但这次没拿到模型评分（" + llmUnavailable
                    + "）：5-gram 只看得出整句照抄，看不出换词照抄，此结论不构成原创证明";
        } else if (llmUnavailable != null) {
            reason += "；且模型评分缺失（" + llmUnavailable + "），这个分数只是本地口径";
        }
        if (strict) {
            throw new ApiException(ErrorCode.BAD_REQUEST, reason + "（strict=true，已阻断进入发布段）");
        }
        return NodeOutput.degraded(data, reason);
    }

    /** json 非空 = 拿到了可解析的评分报文；否则 unavailable 写明是哪一种缺失。 */
    record ModelScore(JsonNode json, String unavailable) {
    }

    /** 模型侧只负责语义判断；任何失败都退回本地算法，不让"校验"变成链路的新单点。 */
    private ModelScore askModel(NodeInput input, String source, String generated) {
        LlmGateway gateway = gatewaySupplier == null ? null : gatewaySupplier.get();
        if (gateway == null) {
            return new ModelScore(null, "LLM 网关未配置");
        }
        String user = "【文章A｜原始参考文章】\n" + clip(source, MAX_SOURCE_CHARS)
                + "\n\n【文章B｜待交付生成文章】\n" + clip(generated, MAX_GENERATED_CHARS);
        String content = null;
        try {
            LlmGateway.Reply reply = gateway.chat(new LlmGateway.Request(
                    input.paramAsString("model", null), PromptTemplates.load("copy-originality.txt"), user, 0.1d));
            content = reply == null ? null : reply.content();
            if (content == null || content.isBlank()) {
                return new ModelScore(null, "模型没有返回内容");
            }
            return new ModelScore(parseModelJson(content), null);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException | java.io.IOException e) {
            // 带上报文头部：模型侧失败时整条链路只剩本地口径，看不到模型回了什么就永远修不动（2026-10-25 执行 17）
            return new ModelScore(null, "模型调用失败：" + e.getClass().getSimpleName()
                    + (content == null ? "" : "，报文头部：" + snippet(content)));
        }
    }

    /**
     * 两级解析。模型有时把 JSON 语法本身写成全角引号，有时又用全角引号在字符串值里摘录原文
     * （`"fragmentA": "“被需要”是一种燃料"`）——一律替换会把后者这份合法 JSON 换碎，
     * 实测正是执行 16/17 那次 JsonParseException 的来源。先按原样解析，失败才退回替换版。
     */
    private JsonNode parseModelJson(String raw) throws java.io.IOException {
        String sliced = extractJson(raw);
        try {
            return mapper.readTree(sliced);
        } catch (java.io.IOException firstAttempt) {
            return mapper.readTree(straightenQuotes(sliced));
        }
    }

    static String straightenQuotes(String s) {
        return s.replace("\u201c", "\"").replace("\u201d", "\"").replace("\u2018", "'").replace("\u2019", "'");
    }

    /** 失败诊断用的报文头部：压平换行、只留 120 字，够定位格式问题又不至于把整篇稿子塞进执行明细。 */
    static String snippet(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** 剥掉 ```json 围栏并取首尾花括号：模板要求只输出 JSON，模型仍常附一句前言。 */
    static String extractJson(String raw) {
        String s = raw.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int body = s.indexOf('\n', fence);
            int end = body > 0 ? s.indexOf("```", body) : -1;
            if (end > body) {
                s = s.substring(body + 1, end).trim();
            }
        }
        int from = s.indexOf('{');
        int to = s.lastIndexOf('}');
        if (from < 0 || to <= from) {
            throw new IllegalArgumentException("回复里没有 JSON 对象");
        }
        return s.substring(from, to + 1);
    }

    private static Map<String, Object> dimensions(JsonNode llm) {
        Map<String, Object> out = new HashMap<>();
        for (String key : new String[] {"wordingSimilarity", "structureSimilarity", "themeSimilarity", "expressionSimilarity"}) {
            if (llm.path(key).isNumber()) {
                out.put(key, llm.path(key).asInt());
            }
        }
        return out;
    }

    private static List<Map<String, Object>> modelFragments(JsonNode llm) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!llm.path("similarFragments").isArray()) {
            return out;
        }
        for (JsonNode node : llm.path("similarFragments")) {
            if (out.size() >= MAX_FRAGMENTS) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fragmentA", node.path("fragmentA").asText(""));
            item.put("fragmentB", node.path("fragmentB").asText(""));
            item.put("similarity", node.path("similarity").asInt(0));
            item.put("note", node.path("note").asText(""));
            out.add(item);
        }
        return out;
    }

    static String riskLevel(int score) {
        return score <= RISK_LOW_MAX ? "low" : score <= RISK_MEDIUM_MAX ? "medium" : "high";
    }

    /** 生成稿优先取创作产物（带 body 的那份输出），与 wechat.draft 同一哲学：采集节点的正文不是新稿。 */
    private static String upstreamGenerated(NodeInput input) {
        for (Map<String, Object> out : orderedUpstream(input)) {
            String body = nonBlank(out.get("body"));
            if (body != null) {
                return body;
            }
        }
        for (Map<String, Object> out : orderedUpstream(input)) {
            String content = nonBlank(out.get("content"));
            if (content != null) {
                return content;
            }
        }
        return "";
    }

    /** 原始文章：article.extract / http.collect 的 text。 */
    private static String upstreamSourceText(NodeInput input) {
        for (Map<String, Object> out : orderedUpstream(input)) {
            String text = nonBlank(out.get("text"));
            if (text != null) {
                return text;
            }
        }
        return "";
    }

    private static List<Map<String, Object>> orderedUpstream(NodeInput input) {
        return input.upstream().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> e.getValue() instanceof Map)
                .<Map<String, Object>>map(e -> (Map<String, Object>) e.getValue())
                .toList();
    }

    private static String nonBlank(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b == null ? "" : b.trim();
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "...[截断]" : s;
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
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

    private static boolean asBoolean(Object v, boolean def) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.equals("false") || t.equals("0") || t.equals("off") || t.equals("no")) {
                return false;
            }
            if (t.equals("true") || t.equals("1") || t.equals("on") || t.equals("yes")) {
                return true;
            }
        }
        return def;
    }

    /**
     * 本地重合度：只比"归一化后的字符 5-gram"，标点/空白/大小写不参与——
     * 排版改一改就想骗过校验是没有的，但正常改词换句不会被误判成抄袭。
     */
    record LocalMatch(double containment, int maxRun, List<String> runs) {

        static LocalMatch of(String source, String generated) {
            String a = normalize(source);
            String b = normalize(generated);
            if (a.length() < GRAM || b.length() < GRAM) {
                return new LocalMatch(0d, 0, List.of());
            }
            Set<String> grams = new HashSet<>();
            for (int i = 0; i + GRAM <= a.length(); i++) {
                grams.add(a.substring(i, i + GRAM));
            }
            int windows = b.length() - GRAM + 1;
            int hits = 0;
            for (int i = 0; i + GRAM <= b.length(); i++) {
                if (grams.contains(b.substring(i, i + GRAM))) {
                    hits++;
                }
            }
            // 逐字扫最长公共片段：命中 5-gram 后一路扩展，直到 source 里不再包含为止
            int maxRun = 0;
            List<String> found = new ArrayList<>();
            int j = 0;
            while (j + GRAM <= b.length()) {
                if (!grams.contains(b.substring(j, j + GRAM))) {
                    j++;
                    continue;
                }
                int end = j + GRAM;
                while (end + 1 <= b.length() && a.contains(b.substring(j, end + 1))) {
                    end++;
                }
                String run = b.substring(j, end);
                found.add(run);
                maxRun = Math.max(maxRun, run.length());
                j = end;
            }
            found.sort(Comparator.comparingInt(String::length).reversed());
            List<String> top = found.stream().limit(MAX_FRAGMENTS).toList();
            return new LocalMatch((double) hits / windows, maxRun, List.copyOf(top));
        }

        private static String normalize(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) {
                    sb.append(c);
                } else if (Character.isLetterOrDigit(c)) {
                    sb.append(Character.toLowerCase(c));
                }
            }
            return sb.toString();
        }
    }
}

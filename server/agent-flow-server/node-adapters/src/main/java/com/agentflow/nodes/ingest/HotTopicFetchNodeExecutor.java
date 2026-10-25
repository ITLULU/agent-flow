package com.agentflow.nodes.ingest;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.support.HttpJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * hotspot.fetch — 热榜聚合节点（自动抓热点数据的入口）。
 *
 * params: sources(逗号分隔或数组，默认 weibo,baidu,toutiao,zhihu) / limit(默认 20，合并去重后条数)
 *         endpointOverride(可选，测试/自建聚合源用：{name: url} 的 JSON 对象字符串)
 * 输出: items[{title,source,hot,url}] / count / okSources[] / failedSources[] / fetchedAt
 *
 * 四件套口径：
 * - 超时：30s 覆盖多源串行；单源用 HttpJson 的 10s 兜底超时（同一口径的下游实现）。
 * - 重试：仅 1 次、800ms 退避——热榜是公开站，激进重试只会被风控，收益为零。
 * - 异常：单源失败不影响其它源（每源独立 try）；全部失败才抛异常走 degrade()。
 * - 降级：输出空 items 续跑——用户自带话题/文章时，热点只是可选燃料，不该断链，degraded=true 显性。
 *         **部分源失败也自报 degraded**（数据照常返回），否则"四源只剩一源"在时间线上看着和"四源全活"一样。
 *
 * 这些榜单接口都是对方网页自用接口，随时可能改结构：解析全程判空，
 * 结构变化表现为 failedSources 增多而非异常，运维可从执行日志直接定位。
 */
public class HotTopicFetchNodeExecutor implements NodeExecutor {

    public static final String TYPE = "hotspot.fetch";
    private static final String DEFAULT_SOURCES = "weibo,baidu,toutiao,zhihu";
    private static final int MAX_ITEMS_HARD = 100;

    /** 一个热榜来源：名称 + 取数地址 + JSON→条目的解析器（解析器必须容忍缺字段）。 */
    record Source(String name, String url, Function<JsonNode, List<Item>> parser) {
    }

    record Item(String title, String source, String hot, String url) {
    }

    private final Map<String, Source> sources;
    private final HttpJson http;

    public HotTopicFetchNodeExecutor() {
        this(Duration.ofSeconds(10));
    }

    public HotTopicFetchNodeExecutor(Duration callTimeout) {
        this(defaultSources(), callTimeout);
    }

    /** 测试/自建源注入口：key=来源名，value=URL（parser 缺省走通用解析）。 */
    public HotTopicFetchNodeExecutor(Map<String, Source> sources, Duration callTimeout) {
        this.sources = sources;
        this.http = new HttpJson(callTimeout);
    }

    static Map<String, Source> defaultSources() {
        Map<String, Source> m = new HashMap<>();
        m.put("weibo", new Source("weibo", "https://weibo.com/ajax/side/hotSearch",
                json -> parseList(json.path("data").path("realtime"),
                        n -> text(n, "word"), n -> text(n, "num"), n -> "")));
        m.put("baidu", new Source("baidu", "https://top.baidu.com/api/board?platform=wise&tab=realtime",
                json -> parseList(firstCard(json.path("data").path("cards")).path("content"),
                        n -> text(n, "word"), n -> text(n, "hotScore"), n -> text(n, "url"))));
        m.put("toutiao", new Source("toutiao", "https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc",
                json -> parseList(json.path("data"),
                        n -> firstNonBlank(text(n, "Title"), text(n, "title")),
                        n -> firstNonBlank(text(n, "HotValue"), text(n, "hot_value")),
                        n -> firstNonBlank(text(n, "Url"), text(n, "url")))));
        // 知乎热榜：条目标题在 data[].target.title_area.text（结构层级最深，故单独解析）
        m.put("zhihu", new Source("zhihu", "https://api.zhihu.com/topstory/hot-lists/total?limit=20",
                json -> parseList(json.path("data"),
                        n -> firstNonBlank(n.path("target").path("title_area").path("text").asText(""),
                                text(n.path("target"), "title")),
                        n -> text(n, "detail_text"),
                        n -> firstNonBlank(text(n.path("target"), "url"), text(n, "url")))));
        return m;
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
        return new RetryPolicy(1, 800L);
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        int limit = clampLimit(asInt(input.param("limit"), 20));
        List<String> wanted = wantedSources(input);
        Map<String, Source> active = overrideSources(input);

        List<Item> merged = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> okSources = new ArrayList<>();
        List<String> failedSources = new ArrayList<>();

        for (String name : wanted) {
            Source src = active.get(name);
            if (src == null) {
                failedSources.add(name + "(未注册)");
                continue;
            }
            try {
                JsonNode json = http.getJson(src.url());
                List<Item> items = src.parser().apply(json);
                int added = 0;
                for (Item it : items) {
                    if (merged.size() >= limit) {
                        break;
                    }
                    if (it.title().isBlank() || !seen.add(normalizeKey(it.title()))) {
                        continue;
                    }
                    // 解析器不感知来源名，合并时统一回填，保证条目可溯源
                    merged.add(new Item(it.title(), name, it.hot(), it.url()));
                    added++;
                }
                if (added == 0) {
                    // 结构变更（字段改名/需登录）时最容易表现为"取到 JSON 但零条目"，必须留痕
                    failedSources.add(name + "(响应无有效条目，疑似接口结构变更)");
                } else {
                    okSources.add(name);
                }
            } catch (ApiException e) {
                failedSources.add(name + "(" + e.errorCode().code() + ": "
                        + HttpJson.brief(e.getMessage()) + ")");
            } catch (RuntimeException e) {
                failedSources.add(name + "(" + e.getClass().getSimpleName() + ")");
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("items", toMaps(merged));
        data.put("count", merged.size());
        data.put("okSources", okSources);
        data.put("failedSources", failedSources);
        data.put("fetchedAt", System.currentTimeMillis());
        if (merged.isEmpty()) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR,
                    "所有热榜来源均取数失败: " + String.join(" | ", failedSources));
        }
        // 部分源失败：数据照常交付，但必须自报降级——四源只剩一源时给用户看"SUCCESS"是误导，
        // 引擎会把这个标志记成 NodeStatus.DEGRADED 并把整次执行升到 SUCCESS_WITH_DEGRADED。
        if (!failedSources.isEmpty()) {
            return NodeOutput.degraded(data, "部分热榜源不可用（已成功 " + okSources.size() + " 个，失败："
                    + String.join(" | ", failedSources) + "），本次条目仅来自存活源");
        }
        return NodeOutput.ok(data);
    }

    @Override
    public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
        Map<String, Object> data = new HashMap<>();
        data.put("items", List.of());
        data.put("count", 0);
        data.put("okSources", List.of());
        data.put("failedSources", List.of());
        return Optional.of(NodeOutput.degraded(data, "热榜不可用，改用用户自带话题继续: " + safeMessage(lastError)));
    }

    private List<String> wantedSources(NodeInput input) {
        Object raw = input.param("sources");
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(v -> out.add(String.valueOf(v).trim()));
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            for (String s : String.valueOf(raw).split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        } else {
            for (String s : DEFAULT_SOURCES.split(",")) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** endpointOverride：{"weibo":"http://127.0.0.1:1234/hot"} —— 换源不改代码，也是单测注入桩的地址口子。 */
    private Map<String, Source> overrideSources(NodeInput input) {
        String ov = input.paramAsString("endpointOverride", null);
        if (ov == null || ov.isBlank()) {
            return sources;
        }
        Map<String, Source> m = new HashMap<>(sources);
        try {
            JsonNode json = HttpJson.mapper().readTree(ov);
            json.fields().forEachRemaining(e -> {
                Source base = sources.get(e.getKey());
                Function<JsonNode, List<Item>> parser = base == null ? HotTopicFetchNodeExecutor::genericParse : base.parser();
                m.put(e.getKey(), new Source(e.getKey(), e.getValue().asText(), parser));
            });
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "endpointOverride 不是合法 JSON 对象: " + HttpJson.brief(ov));
        }
        return m;
    }

    /** 未注册来源的兜底解析：在常见字段名里找标题，找不到就跳过。 */
    static List<Item> genericParse(JsonNode json) {
        JsonNode arr = json.isArray() ? json : firstArrayNode(json);
        return parseList(arr, n -> firstNonBlank(text(n, "title"), text(n, "word"), text(n, "Title")),
                n -> firstNonBlank(text(n, "hot"), text(n, "hotScore"), text(n, "num")),
                n -> text(n, "url"));
    }

    private static List<Map<String, Object>> toMaps(List<Item> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item it : items) {
            Map<String, Object> m = new HashMap<>();
            m.put("title", it.title());
            m.put("source", it.source());
            m.put("hot", it.hot());
            m.put("url", it.url());
            out.add(m);
        }
        return out;
    }

    private static List<Item> parseList(JsonNode arr, Function<JsonNode, String> title,
                                         Function<JsonNode, String> hot, Function<JsonNode, String> url) {
        List<Item> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (out.size() >= MAX_ITEMS_HARD) {
                break;
            }
            String t = title.apply(n);
            if (t == null || t.isBlank()) {
                continue;
            }
            out.add(new Item(t.trim(), "", nullToEmpty(hot.apply(n)), nullToEmpty(url.apply(n))));
        }
        return out;
    }

    private static JsonNode firstCard(JsonNode cards) {
        return cards != null && cards.isArray() && cards.size() > 0 ? cards.get(0) : cards;
    }

    private static JsonNode firstArrayNode(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return root;
        }
        if (root.isArray()) {
            return root;
        }
        for (JsonNode child : root) {
            JsonNode found = firstArrayNode(child);
            if (found != null && found.isArray() && found.size() > 0) {
                return found;
            }
        }
        return root;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.path(field);
        if (v == null || v.isMissingNode() || v.isNull()) {
            return "";
        }
        return v.isNumber() ? v.asText() : v.asText("");
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String normalizeKey(String title) {
        return title.replaceAll("\\s+", "").toLowerCase();
    }

    private static int clampLimit(int v) {
        if (v <= 0) {
            return 20;
        }
        return Math.min(v, MAX_ITEMS_HARD);
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

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}

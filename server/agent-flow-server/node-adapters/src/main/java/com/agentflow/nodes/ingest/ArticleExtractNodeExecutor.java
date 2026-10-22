package com.agentflow.nodes.ingest;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.support.HttpJson;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * article.extract — 正文抽取节点（给一篇公众号文章/任意链接，取出可创作的正文素材）。
 *
 * params: url(必填) / maxChars(默认 12000) / selector(可选，强制指定正文容器) / includeImages(默认 true)
 * 输出: url / title / author / publishDate / text / paragraphs[](≤200) / images[](≤30) / wordCount
 *
 * 与 http.collect 的分工：http.collect 是"结构化列表抓取"（选择器命中一堆条目），
 * 本节点是"单篇正文提纯"（按文本密度挑正文容器，剥掉导航/推荐/评论区噪声），两者不互相替代。
 *
 * 四件套口径：超时 25s；重试 1 次（反爬站点重试多了只会被封）；
 * 异常三类照 HttpJson；降级=空正文续跑（素材源之一失败不该断链，degraded 显性）。
 */
public class ArticleExtractNodeExecutor implements NodeExecutor {

    public static final String TYPE = "article.extract";

    /** 候选正文容器：公众号优先，其次通用语义标签与常见 CMS 类名。 */
    private static final String[] CANDIDATES = {
            "#js_content", ".rich_media_content", "article", "#article-content", ".article-content",
            ".post-content", ".entry-content", ".article", "#content", ".content", "body"
    };
    private static final String[] NOISE = {"script", "style", "noscript", "iframe", "nav", "header",
            "footer", "form", ".reward", "#js_pc_qr_code", ".rich_media_meta_list", "blockquote.js_blockquote"};
    private static final int MAX_PARAGRAPHS = 200;
    private static final int MAX_IMAGES = 30;
    private static final int MIN_BODY_CHARS = 200;

    private final HttpJson http;

    public ArticleExtractNodeExecutor() {
        this(Duration.ofSeconds(20));
    }

    public ArticleExtractNodeExecutor(Duration callTimeout) {
        this.http = new HttpJson(callTimeout);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(25);
    }

    @Override
    public RetryPolicy retryPolicy() {
        return new RetryPolicy(1, 1000L);
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        String url = input.paramAsString("url", null);
        if (url == null || url.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "article.extract 缺少必填参数 url");
        }
        int maxChars = asInt(input.param("maxChars"), 12000);
        String forced = input.paramAsString("selector", null);
        boolean wantImages = asBool(input.param("includeImages"), true);

        Document doc = Jsoup.parse(http.get(url), url);
        for (String sel : NOISE) {
            doc.select(sel).remove();
        }

        Element container = forced != null && !forced.isBlank()
                ? firstMatch(doc, forced)
                : pickByDensity(doc);
        if (container == null) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR,
                    url + " 未找到正文容器（selector=" + forced + "），可换 selector 参数重试");
        }

        List<String> paragraphs = new ArrayList<>();
        for (Element p : container.select("p, li, h2, h3, blockquote")) {
            String t = p.text().trim();
            if (!t.isEmpty() && paragraphs.size() < MAX_PARAGRAPHS) {
                paragraphs.add(t);
            }
        }
        String text = normalize(container.text());
        if (paragraphs.isEmpty() && !text.isBlank()) {
            paragraphs.add(text);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("url", url);
        data.put("title", extractTitle(doc));
        data.put("author", extractMeta(doc, "article:author", "author", ".rich_media_meta_nickname"));
        data.put("publishDate", extractMeta(doc, "article:published_time", "og:release_date", ""));
        data.put("text", text.length() > maxChars ? text.substring(0, maxChars) + "...[截断]" : text);
        data.put("paragraphs", paragraphs);
        data.put("wordCount", countWords(text));
        if (wantImages) {
            data.put("images", collectImages(container, url));
        }
        return NodeOutput.ok(data);
    }

    @Override
    public Optional<NodeOutput> degrade(NodeInput input, Throwable lastError) {
        Map<String, Object> data = new HashMap<>();
        data.put("url", input.paramAsString("url", ""));
        data.put("title", "");
        data.put("text", "");
        data.put("paragraphs", List.of());
        data.put("images", List.of());
        data.put("wordCount", 0);
        return Optional.of(NodeOutput.degraded(data, "正文抽取失败，按无原文素材继续: " + safeMessage(lastError)));
    }

    /** 在候选容器里挑"正文量最大"的那个：先取有足够文本的首个候选，再在候选间比字数。 */
    private static Element pickByDensity(Document doc) {
        Element best = null;
        int bestLen = -1;
        for (String sel : CANDIDATES) {
            Element el = firstMatch(doc, sel);
            if (el == null) {
                continue;
            }
            int len = el.text().length();
            if (len > bestLen) {
                bestLen = len;
                best = el;
            }
            if (bestLen >= MIN_BODY_CHARS && !"#content".equals(sel) && !".content".equals(sel)) {
                // 命中语义容器且够长即可停：#content/.content/body 太泛，留给后面比字数
                break;
            }
        }
        return bestLen < MIN_BODY_CHARS && doc.body() != null && best == null ? doc.body() : best;
    }

    private static String extractTitle(Document doc) {
        Element ogEl = firstMatch(doc, "meta[property=og:title]");
        String og = ogEl == null ? null : ogEl.attr("content");
        if (og != null && !og.isBlank()) {
            return og.trim();
        }
        Element h1 = firstMatch(doc, "h1");
        if (h1 != null && !h1.text().isBlank()) {
            return h1.text().trim();
        }
        return doc.title().trim();
    }

    /** jsoup 1.10.2（版本钉死，见父 pom）没有 Document.selectFirst，用 select().get(0) 收敛成一处。 */
    private static Element firstMatch(Element root, String css) {
        List<Element> found = root.select(css);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String extractMeta(Document doc, String prop1, String prop2, String cssClass) {
        if (prop1 != null && !prop1.isBlank()) {
            Element m = firstMatch(doc, "meta[property=" + prop1 + "]");
            if (m != null && !m.attr("content").isBlank()) {
                return m.attr("content").trim();
            }
        }
        if (prop2 != null && !prop2.isBlank()) {
            Element m = firstMatch(doc, "meta[name=" + prop2 + "]");
            if (m != null && !m.attr("content").isBlank()) {
                return m.attr("content").trim();
            }
        }
        if (cssClass != null && !cssClass.isBlank()) {
            Element e = firstMatch(doc, cssClass);
            if (e != null && !e.text().isBlank()) {
                return e.text().trim();
            }
        }
        return "";
    }

    /** 正文配图（原图 URL 已绝对化）：供"配图复用"或素材参考，不含 data URI 与占位图。 */
    private static List<String> collectImages(Element container, String baseUri) {
        List<String> out = new ArrayList<>();
        Elements els = container.select("img[src]");
        for (Element img : els) {
            if (out.size() >= MAX_IMAGES) {
                break;
            }
            String abs = img.absUrl("src");
            if (abs.isBlank() || abs.startsWith("data:")) {
                continue;
            }
            out.add(abs);
        }
        return out;
    }

    private static String normalize(String raw) {
        return raw.replaceAll("[\\t ]+", " ").replaceAll("\n{3,}", "\n\n").trim();
    }

    /** 中文按字符计、英文按词计，与创作者习惯口径一致（只做素材规模参考，不做字数硬校验）。 */
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

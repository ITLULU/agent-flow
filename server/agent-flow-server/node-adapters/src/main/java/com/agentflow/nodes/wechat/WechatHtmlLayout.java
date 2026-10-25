package com.agentflow.nodes.wechat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公众号正文排版器：把创作节点产出的纯文本渲染成"能直接看"的微信正文 HTML。
 *
 * 为什么必须全内联 style：微信编辑器会清洗 class 属性与 &lt;style&gt; 块，只保留元素上的 style，
 * 所以任何"外部样式表"式排版进草稿箱后都会退化成没有格式的裸段落（2026-10-25 实测交付物就是这个样子）。
 *
 * 层次来自三处：自动编号的小标题色条、段落间距与字距、引用/列表/插图的差异化卡片。
 * 输入是模型产出的 Markdown 风格纯文本（## 小标题、**重点**、&gt; 金句、- 要点）。
 */
public final class WechatHtmlLayout {

    /** 一套配色即一种观感；默认 moss（低饱和墨绿），比纯蓝纯红更接近内容号审美。 */
    public record Theme(String name, String accent, String tint, String text, String sub) {
    }

    private static final Map<String, Theme> THEMES = new LinkedHashMap<>();

    static {
        register(new Theme("moss", "#2f6f5e", "#f4f8f6", "#3b3f3d", "#7b8781"));
        register(new Theme("azure", "#2b6cb0", "#f2f6fb", "#33393f", "#78838f"));
        register(new Theme("amber", "#b45309", "#fbf6ef", "#3f382f", "#8a7f6f"));
        register(new Theme("ink", "#1f2937", "#f5f6f7", "#2d3439", "#838a91"));
    }

    private static void register(Theme theme) {
        THEMES.put(theme.name(), theme);
    }

    public static Theme defaultTheme() {
        return THEMES.get("moss");
    }

    /** 未知名返回 null，由调用方转成配置错误——排版器不猜用户想要哪套配色。 */
    public static Theme themeOrNull(String name) {
        return name == null || name.isBlank() ? defaultTheme() : THEMES.get(name.trim().toLowerCase());
    }

    public static List<String> themeNames() {
        return List.copyOf(THEMES.keySet());
    }

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,4}\\s*(.+?)\\s*$");
    private static final Pattern CN_HEADING = Pattern.compile("^([一二三四五六七八九十]+、|第[一二三四五六七八九十百零〇]+[节章部篇])[^。！？.]{1,28}$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+·•]|\\d+[.、)])\\s+(.+)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s*(.+)$");
    private static final Pattern DIVIDER = Pattern.compile("^\\s*(?:-{3,}|\\*{3,})\\s*$");
    private static final Pattern MD_IMAGE = Pattern.compile("^!\\[([^\\]]*)\\]\\((\\S+)\\)\\s*$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    private WechatHtmlLayout() {
    }

    /** images 为空即纯文字排版；非空则按小节均匀插入，没有小节就按段落插入。 */
    public static String render(String raw, Theme theme, List<String> images) {
        List<Block> blocks = parse(raw);
        if (blocks.isEmpty()) {
            return "";
        }
        insertImages(blocks, images);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<section style=\"font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif;")
                .append("font-size:16px;line-height:1.85;letter-spacing:0.5px;color:").append(theme.text())
                .append(";word-break:break-word;text-align:justify\">");
        int headingIndex = 0;
        for (Block b : blocks) {
            switch (b.kind) {
                case HEADING -> {
                    headingIndex++;
                    sb.append(heading(theme, headingIndex, b.text));
                }
                case PARAGRAPH -> sb.append(paragraph(theme, b.text));
                case QUOTE -> sb.append(quote(theme, b.text));
                case LIST -> sb.append(list(theme, b.text));
                case IMAGE -> sb.append(image(theme, b.text, b.alt));
                case DIVIDER -> sb.append(divider(theme));
            }
        }
        return sb.append("</section>").toString();
    }

    /** 作者自带 HTML 时不能重排他的结构，配图只能追加在文末（宁可放在末尾，也不要悄悄丢图）。 */
    public static String appendImages(String html, Theme theme, List<String> images) {
        if (images == null || images.isEmpty()) {
            return html;
        }
        StringBuilder sb = new StringBuilder(html);
        for (String url : images) {
            sb.append(image(theme, url, null));
        }
        return sb.toString();
    }

    // ---- 块级渲染 ----

    private static String heading(Theme t, int index, String text) {
        String no = index < 10 ? "0" + index : String.valueOf(index);
        return "<section style=\"margin:40px 0 20px;line-height:1\">"
                + "<span style=\"display:inline-block;font-size:12px;font-weight:700;color:#ffffff;background:" + t.accent()
                + ";padding:3px 9px;border-radius:10px 3px 10px 3px;letter-spacing:1px;vertical-align:2px\">" + no + "</span>"
                + "<span style=\"font-size:18px;font-weight:700;color:" + t.text() + ";letter-spacing:0.5px;margin-left:10px\">"
                + inline(t, text) + "</span>"
                + "<section style=\"height:2px;margin:12px 0 0;background:linear-gradient(90deg," + t.accent() + " 0%,rgba(255,255,255,0) 78%)\"></section>"
                + "</section>";
    }

    private static String paragraph(Theme t, String text) {
        return "<p style=\"margin:0 0 22px;font-size:16px;line-height:1.9;letter-spacing:0.5px;color:" + t.text() + "\">"
                + inline(t, text) + "</p>";
    }

    private static String quote(Theme t, String text) {
        return "<section style=\"margin:26px 0;padding:16px 18px;background:" + t.tint()
                + ";border-left:3px solid " + t.accent() + ";border-radius:0 10px 10px 0\">"
                + "<p style=\"margin:0;font-size:15px;line-height:1.85;color:" + t.sub() + "\">" + inline(t, text) + "</p>"
                + "</section>";
    }

    private static String list(Theme t, String text) {
        return "<section style=\"margin:0 0 22px\">"
                + "<section style=\"margin:0 0 10px;line-height:1.8\">"
                + "<span style=\"display:inline-block;width:5px;height:5px;background:" + t.accent()
                + ";border-radius:50%;vertical-align:5px;margin-right:10px\"></span>"
                + "<span style=\"font-size:15.5px;color:" + t.text() + "\">" + inline(t, text) + "</span>"
                + "</section></section>";
    }

    private static String image(Theme t, String url, String caption) {
        String cap = caption == null || caption.isBlank() ? ""
                : "<p style=\"margin:8px 0 0;font-size:13px;color:" + t.sub() + ";text-align:center\">" + inline(t, caption) + "</p>";
        return "<section style=\"margin:30px 0;text-align:center\">"
                + "<img src=\"" + escapeAttr(url) + "\" alt=\"" + escapeAttr(caption == null ? "" : caption)
                + "\" style=\"max-width:100%;width:100%;border-radius:12px;display:block;margin:0 auto\" />"
                + cap + "</section>";
    }

    private static String divider(Theme t) {
        return "<section style=\"margin:34px 0;text-align:center;letter-spacing:6px;font-size:12px;color:" + t.sub()
                + "\">· · ·</section>";
    }

    // ---- 解析 ----

    private enum Kind {
        HEADING, PARAGRAPH, QUOTE, LIST, IMAGE, DIVIDER
    }

    private static final class Block {
        Kind kind;
        String text;
        String alt;

        Block(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    /**
     * 连续的非空普通行合并成一段：中文正文的换行是软换行（模型按 2~4 行排版），
     * 若逐行切段会把一个论点拆成一串短段落，读者看到的是碎、不是层次。
     */
    private static List<Block> parse(String raw) {
        List<Block> blocks = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return blocks;
        }
        StringBuilder para = new StringBuilder();
        for (String line : raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                flush(para, blocks);
                continue;
            }
            if (DIVIDER.matcher(s).matches()) {
                flush(para, blocks);
                blocks.add(new Block(Kind.DIVIDER, ""));
                continue;
            }
            Matcher img = MD_IMAGE.matcher(s);
            if (img.matches()) {
                flush(para, blocks);
                Block b = new Block(Kind.IMAGE, img.group(2));
                b.alt = img.group(1);
                blocks.add(b);
                continue;
            }
            Matcher h = HEADING.matcher(s);
            if (h.matches()) {
                flush(para, blocks);
                blocks.add(new Block(Kind.HEADING, stripTrailingPunctuation(h.group(1))));
                continue;
            }
            if (CN_HEADING.matcher(s).matches()) {
                flush(para, blocks);
                blocks.add(new Block(Kind.HEADING, stripTrailingPunctuation(s)));
                continue;
            }
            Matcher q = QUOTE.matcher(s);
            if (q.matches()) {
                flush(para, blocks);
                blocks.add(new Block(Kind.QUOTE, q.group(1)));
                continue;
            }
            Matcher li = LIST_ITEM.matcher(s);
            if (li.matches()) {
                flush(para, blocks);
                blocks.add(new Block(Kind.LIST, li.group(1)));
                continue;
            }
            if (para.length() > 0) {
                para.append(' ');
            }
            para.append(s);
        }
        flush(para, blocks);
        return blocks;
    }

    private static void flush(StringBuilder para, List<Block> blocks) {
        if (para.length() == 0) {
            return;
        }
        blocks.add(new Block(Kind.PARAGRAPH, para.toString()));
        para.setLength(0);
    }

    private static String stripTrailingPunctuation(String s) {
        return s.replaceAll("[。．.，,；;：:]+$", "");
    }

    /** 插图按小节平分；小节不足时退化为按段落平分，保证图落在内容脉络上而不是堆在开头或结尾。 */
    private static void insertImages(List<Block> blocks, List<String> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        List<Integer> anchors = anchorIndexes(blocks, Kind.HEADING);
        if (anchors.size() < images.size()) {
            anchors = anchorIndexes(blocks, Kind.PARAGRAPH);
        }
        if (anchors.isEmpty()) {
            blocks.add(imageBlock(images.get(0)));
            return;
        }
        Map<Integer, List<Block>> pending = new LinkedHashMap<>();
        for (int j = 0; j < images.size(); j++) {
            int anchor = anchors.get((int) ((long) j * anchors.size() / images.size()));
            pending.computeIfAbsent(anchor, k -> new ArrayList<>()).add(imageBlock(images.get(j)));
        }
        List<Block> out = new ArrayList<>(blocks.size() + images.size());
        for (int i = 0; i < blocks.size(); i++) {
            out.add(blocks.get(i));
            out.addAll(pending.getOrDefault(i, List.of()));
        }
        blocks.clear();
        blocks.addAll(out);
    }

    private static List<Integer> anchorIndexes(List<Block> blocks, Kind kind) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).kind == kind) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static Block imageBlock(String url) {
        return new Block(Kind.IMAGE, url);
    }

    // ---- 行内 ----

    private static String inline(Theme t, String text) {
        String escaped = escapeHtml(text);
        Matcher m = BOLD.matcher(escaped);
        StringBuilder out = new StringBuilder(escaped.length() + 32);
        while (m.find()) {
            m.appendReplacement(out,
                Matcher.quoteReplacement("<strong style=\"font-weight:700;color:" + t.accent() + "\">" + m.group(1) + "</strong>"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeAttr(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

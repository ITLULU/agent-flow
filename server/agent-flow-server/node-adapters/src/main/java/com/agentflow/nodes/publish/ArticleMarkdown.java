package com.agentflow.nodes.publish;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 成品 markdown 渲染与文件名（从 publish.local 抽出）。
 * 抽成独立一处的原因：补偿入口（repair）必须能从数据库里的 title/body/meta 重渲染出**逐字节相同**的文件，
 * 否则"修好了"只是又写了一份格式漂移的新稿，人工比对时最难发现的那种脏数据。
 */
public final class ArticleMarkdown {

    /** 存量成品（meta 里没有 markdownFileName）的旧文件名，repair 需要照旧重建，不能改名造出第二份。 */
    public static final String LEGACY_MARKDOWN_FILE = "article.md";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 存储层文件名上限 96 字符，这里留出时间戳与扩展名的余量，避免标题把时间戳截掉。 */
    private static final int MAX_STEM_CHARS = 60;

    private ArticleMarkdown() {
    }

    public static String stamp(LocalDateTime now) {
        return STAMP.format(now);
    }

    /** 本地成品名 = 生成文标题 + 归档时刻，重跑不覆盖旧稿（用户口径：article.md 会被下一次跑冲掉）。 */
    public static String markdownFileName(String title, String stamp) {
        return safeStem(title) + "-" + stamp + ".md";
    }

    /** 文件名安全化：只留中英文、数字、连字符；表情与标点换成连接符，长度受限。 */
    static String safeStem(String title) {
        if (title == null || title.isBlank()) {
            return "article";
        }
        String cleaned = title.trim()
                .replaceAll("[\\s\\p{Punct}]+", "-")
                .replaceAll("[^\\p{L}\\p{N}-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (cleaned.isBlank()) {
            return "article";
        }
        return cleaned.length() <= MAX_STEM_CHARS ? cleaned : cleaned.substring(0, MAX_STEM_CHARS);
    }

    public static String render(String kind, String title, String digest, String body, List<String> hashtags) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(yaml(title)).append("\n");
        sb.append("kind: ").append(kind).append("\n");
        if (digest != null && !digest.isBlank()) {
            sb.append("digest: ").append(yaml(digest)).append("\n");
        }
        if (hashtags != null && !hashtags.isEmpty()) {
            sb.append("hashtags: [").append(String.join(", ",
                    hashtags.stream().map(ArticleMarkdown::yaml).toList())).append("]\n");
        }
        sb.append("---\n\n");
        sb.append("# ").append(title).append("\n\n");
        if (digest != null && !digest.isBlank()) {
            sb.append("> ").append(digest).append("\n\n");
        }
        sb.append(body.trim()).append("\n");
        if (hashtags != null && !hashtags.isEmpty()) {
            sb.append("\n").append(String.join(" ", hashtags)).append("\n");
        }
        return sb.toString();
    }

    private static String yaml(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}

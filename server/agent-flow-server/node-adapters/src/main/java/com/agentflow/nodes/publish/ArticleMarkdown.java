package com.agentflow.nodes.publish;

import java.util.List;

/**
 * 成品 markdown 渲染（从 publish.local 抽出）。
 * 抽成独立一处的原因：补偿入口（repair）必须能从数据库里的 title/body/meta 重渲染出**逐字节相同**的文件，
 * 否则"修好了"只是又写了一份格式漂移的新稿，人工比对时最难发现的那种脏数据。
 */
public final class ArticleMarkdown {

    private ArticleMarkdown() {
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

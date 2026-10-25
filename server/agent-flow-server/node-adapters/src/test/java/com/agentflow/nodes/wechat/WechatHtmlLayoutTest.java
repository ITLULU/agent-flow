package com.agentflow.nodes.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WechatHtmlLayoutTest {

    private static String render(String raw, List<String> images) {
        return WechatHtmlLayout.render(raw, WechatHtmlLayout.defaultTheme(), images);
    }

    @Test
    void everyStyleIsInlineBecauseWechatStripsClasses() {
        String html = render("第一段。\n\n第二段。", List.of());

        assertThat(html).doesNotContain("class=").doesNotContain("<style");
        assertThat(html).contains("<p style=\"margin:0 0 22px");
        assertThat(html).startsWith("<section style=\"font-family:");
    }

    @Test
    void paragraphsKeepTheirOwnSpacingAndEscapeAngleBrackets() {
        String html = render("A & B\n<div>不该被当成标签</div>\n\n第二段。", List.of());

        assertThat(html).contains("A &amp; B").contains("&lt;div&gt;").doesNotContain("<div>");
        assertThat(html).contains("第二段。");
    }

    @Test
    void softLineBreaksInsideOneParagraphAreJoinedNotShattered() {
        // 模型按 2~4 行排一个论点，逐行切段会让读者看到的是碎而不是层次
        String html = render("先把结论说完\n再说第二句\n\n下一个段落。", List.of());

        assertThat(html).contains("先把结论说完 再说第二句");
        assertThat(html.split("<p ").length - 1).isEqualTo(2);
    }

    @Test
    void hashHeadingsBecomeNumberedAccentBlocks() {
        String html = render("正文起手。\n\n## 先说结论\n\n这一节的内容。\n\n## 怎么做\n\n那条的内容。", List.of());

        assertThat(html).contains(">01</span>").contains(">02</span>");
        assertThat(html).contains("先说结论").contains("怎么做");
        assertThat(html).contains("linear-gradient"); // 小标题下的色条收尾
    }

    @Test
    void chineseNumberedHeadingsAreDetectedEvenWithoutHash() {
        String html = render("导语一段。\n\n二、换一个场景\n\n正文。", List.of());

        assertThat(html).contains("margin-left:10px\">二、换一个场景</span>").contains(">01</span>");
    }

    @Test
    void boldBecomesAccentStrongAndQuoteBecomesCard() {
        String html = render("这里有个**关键结论**要强调。\n\n> 金句：能记住的才是重点。", List.of());

        assertThat(html).contains("<strong style=\"font-weight:700;color:#2f6f5e\">关键结论</strong>");
        assertThat(html).contains("border-left:3px solid #2f6f5e").contains("能记住的才是重点");
    }

    @Test
    void listItemsGetBulletMarkersAndDividerRenders() {
        String html = render("- 要点甲\n- 要点乙\n\n---\n\n收尾段落。", List.of());

        assertThat(html).contains("border-radius:50%").contains("要点甲").contains("要点乙");
        assertThat(html).contains("· · ·");
    }

    @Test
    void imagesAreSpreadAcrossSectionsNotStackedAtOneEnd() {
        String body = "## 第一节\n\n内容甲。\n\n## 第二节\n\n内容乙。\n\n## 第三节\n\n内容丙。";
        String html = render(body, List.of("https://mmbiz.qq.com/a.jpg", "https://mmbiz.qq.com/b.jpg", "https://mmbiz.qq.com/c.jpg"));

        assertThat(html).contains("src=\"https://mmbiz.qq.com/a.jpg\"")
                .contains("src=\"https://mmbiz.qq.com/b.jpg\"")
                .contains("src=\"https://mmbiz.qq.com/c.jpg\"");
        int first = html.indexOf("a.jpg");
        int last = html.indexOf("c.jpg");
        assertThat(html.indexOf("第二节")).isGreaterThan(first).isLessThan(last); // 图跟着小节走
    }

    @Test
    void moreImagesThanSectionsFallsBackToParagraphAnchors() {
        String html = render("段一。\n\n段二。", List.of("https://x/1.png", "https://x/2.png", "https://x/3.png"));

        assertThat(html).contains("1.png", "2.png", "3.png");
        assertThat(html).contains("max-width:100%");
    }

    @Test
    void markdownImageLineKeepsItsCaption() {
        String html = render("![现场示意图](https://x/p.png)", List.of());

        assertThat(html).contains("alt=\"现场示意图\"").contains("现场示意图</p>");
    }

    @Test
    void themesAreSelectableAndUnknownNamesAreNotGuessed() {
        assertThat(WechatHtmlLayout.themeOrNull(null)).isSameAs(WechatHtmlLayout.defaultTheme());
        assertThat(WechatHtmlLayout.themeOrNull("AMBER").accent()).isEqualTo("#b45309");
        assertThat(WechatHtmlLayout.themeOrNull("purple")).isNull();
        assertThat(WechatHtmlLayout.themeNames()).contains("moss", "azure", "amber", "ink");
        assertThat(render("内容", List.of())).contains(WechatHtmlLayout.defaultTheme().text());
    }

    @Test
    void blankInputRendersNothingSoCallerCanFailLoud() {
        assertThat(render("   ", List.of())).isEmpty();
        assertThat(WechatHtmlLayout.render(null, WechatHtmlLayout.defaultTheme(), List.of())).isEmpty();
    }

    @Test
    void quotesInAttributeContextCannotBreakOut() {
        String html = render("![坏\"引号](https://x/1.png?a=\"b\")", List.of());

        assertThat(html).doesNotContain("alt=\"坏\"").doesNotContain("src=\"https://x/1.png?a=\"b\"");
        assertThat(html).contains("alt=\"坏&quot;引号\"").contains("border-radius:12px");
    }

    @Test
    void malformedMarkdownStaysVisibleText() {
        // 半截的 ![]( 不能被当成图片解析出随意 src：宁可让读者看到这行写坏了
        assertThat(render("![缺右括号](https://x/1.png", List.of())).contains("![缺右括号]").doesNotContain("<img");
    }
}

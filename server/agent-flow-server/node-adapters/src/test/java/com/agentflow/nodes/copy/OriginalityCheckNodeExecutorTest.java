package com.agentflow.nodes.copy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.nodes.llm.LlmGateway;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * copy.originality：相似度比的两条腿（本地重合度 + LLM 四维）、阈值语义（degraded 不阻断 / strict 阻断）、
 * 以及"模型挂了也要给出可复现的数"这三件事。
 */
class OriginalityCheckNodeExecutorTest {

    private static final ExecutionContext CTX = new ExecutionContext("t-1", 1L, 1L);

    private static final String SOURCE = """
            公司要求每周交周报，很多人把它当成负担，写到深夜还在拼凑字数。其实周报的第一读者不是领导而是自己，\
            把它写成一份下周能照着执行的说明书，注意力就从"被检查"回到了"我在推进"。
            """;

    /** 彻底改写：主题相近，但措辞、例子、结构都换掉（这才是我们想要的原创）。 */
    private static final String REWRITTEN = """
            例行文档之所以让人烦躁，是因为它被当成了作业。换个用法就顺了：周四下午花十分钟，把本周卡住的两个点、\
            下周要动的三件事列成清单，周一早上照着清单开工。写的人省下了回忆的力气，读的人也不必再追问进度。
            """;

    /** 只改标点和换行、字全一样：归一化之后必须照样判成照抄。 */
    private static final String REPUNCTUATED = SOURCE.replace("，", " 。 ").replace("；", "\n\n");

    private static final String LLM_JSON = """
            ```json
            {"similarityScore": 78, "riskLevel": "high", "wordingSimilarity": 90, "structureSimilarity": 70,
             "themeSimilarity": 60, "expressionSimilarity": 80, "summary": "整句沿用原文表述",
             "similarFragments": [{"fragmentA": "第一读者不是领导", "fragmentB": "第一读者并非领导", "similarity": 85, "note": "换个说法照抄"}]}
            ```""";

    /** 模型认为确实改写了（配合彻底改写的稿子，唯一能给出"绿色通过"的组合）。 */
    private static final String LLM_CLEAN_JSON = """
            {"similarityScore": 8, "wordingSimilarity": 10, "structureSimilarity": 5,
             "themeSimilarity": 40, "expressionSimilarity": 6, "summary": "措辞与结构均已重写"}""";

    private static NodeOutput run(Map<String, Object> params, Map<String, Object> upstream, LlmGateway gateway) {
        return new OriginalityCheckNodeExecutor(() -> gateway).execute(new NodeInput(params, upstream), CTX);
    }

    private static NodeOutput run(Map<String, Object> params) {
        return run(params, Map.of(), new StubGateway(LLM_JSON));
    }

    private static final class StubGateway implements LlmGateway {
        private final String answer;
        Request last;

        StubGateway(String answer) {
            this.answer = answer;
        }

        @Override
        public Reply chat(Request request) {
            this.last = request;
            return answer == null ? null : new Reply(answer, "qwen-plus", 500);
        }
    }

    @Test
    void copyPasteIsHighRiskAndDegradedNotSilentPass() {
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REPUNCTUATED), Map.of(), null);

        assertThat(out.degraded()).isTrue();
        assertThat((int) out.data().get("similarityScore")).isGreaterThan(50);
        assertThat(out.data()).containsEntry("riskLevel", "high")
                .containsEntry("method", "local-only");
        assertThat((int) out.data().get("maxCommonRun")).isGreaterThan(24);
        assertThat(out.degradeReason()).contains("照抄级别");
        assertThat((java.util.List<?>) out.data().get("commonFragments")).isNotEmpty();
    }

    @Test
    void thoroughRewritePassesClean() {
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REWRITTEN), Map.of(), new StubGateway(LLM_CLEAN_JSON));

        assertThat(out.degraded()).isFalse();
        assertThat((int) out.data().get("similarityScore")).isLessThanOrEqualTo(OriginalityCheckNodeExecutor.RISK_LOW_MAX);
        assertThat(out.data()).containsEntry("riskLevel", "low").containsEntry("method", "llm+local")
                .doesNotContainKey("llmNote");
        assertThat((double) out.data().get("localOverlap")).isLessThan(0.1d);
    }

    /**
     * 本地 5-gram 只看得出整句照抄。实测同一篇参考文章：执行 15 模型口径 68/high，执行 16 模型缺位时本地 0/low——
     * 那个 0 是假绿，必须显性降级，否则"没校验"会被读成"校验通过"。
     */
    @Test
    void missingModelScoreDowngradesEvenACleanLocalScore() {
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REWRITTEN), Map.of(), null);

        assertThat(out.degraded()).isTrue();
        assertThat(out.data()).containsEntry("method", "local-only");
        assertThat(out.data()).containsEntry("llmNote", "LLM 网关未配置");
        assertThat(out.degradeReason()).contains("不构成原创证明");
    }

    @Test
    void strictModeBlocksWhenModelScoreIsMissing() {
        assertThatThrownBy(() -> run(Map.of("source", SOURCE, "generated", REWRITTEN, "strict", true), Map.of(), null))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getMessage()).contains("没拿到模型评分").contains("已阻断进入发布段"));
    }

    @Test
    void modelReplyWithoutScoreCountsAsMissingNotZero() {
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REWRITTEN), Map.of(),
                new StubGateway("{\"summary\": \"看着不太像抄\"}"));

        assertThat(out.degraded()).isTrue();
        assertThat(out.data()).doesNotContainKey("llmScore")
                .containsEntry("method", "local-only");
        assertThat((String) out.data().get("llmNote")).contains("similarityScore");
    }

    @Test
    void localScoreWinsWhenModelIsTooOptimistic() {
        // 反方向也要拦：模型说"几乎不相似"，但本地扫到整段连续相同 ⇒ 取更严的那个
        StubGateway generous = new StubGateway("{\"similarityScore\": 3, \"summary\": \"改写充分\"}");
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REPUNCTUATED), Map.of(), generous);

        assertThat(out.degraded()).isTrue();
        assertThat((int) out.data().get("llmScore")).isEqualTo(3);
        assertThat((int) out.data().get("similarityScore")).isGreaterThanOrEqualTo(60);
        assertThat(out.data()).containsEntry("method", "llm+local");
    }

    @Test
    void strictModeBlocksBeforePublish() {
        assertThatThrownBy(() -> run(Map.of("source", SOURCE, "generated", REPUNCTUATED, "strict", true), Map.of(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("已阻断进入发布段");
                });
    }

    @Test
    void modelScoreWinsWhenItIsTheStricterOne() {
        // 本地只看到"改写得很干净"，模型却识别出换词照抄 ⇒ 取更严的那个，两侧任一漏判都不放行
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REWRITTEN), Map.of(), new StubGateway(LLM_JSON));

        assertThat(out.data()).containsEntry("similarityScore", 78)
                .containsEntry("riskLevel", "high")
                .containsEntry("method", "llm+local");
        assertThat(out.degraded()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) out.data().get("dimensions");
        assertThat(dims).containsEntry("wordingSimilarity", 90).containsEntry("themeSimilarity", 60);
        assertThat((java.util.List<?>) out.data().get("modelFragments")).hasSize(1);
    }

    @Test
    void gatewayFailureStillDeliversLocalScore() {
        LlmGateway boom = request -> {
            throw new IllegalStateException("SDK 炸了");
        };
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REPUNCTUATED), Map.of(), boom);

        assertThat(out.data()).containsEntry("method", "local-only");
        assertThat((String) out.data().get("llmNote")).contains("模型调用失败");
        assertThat((int) out.data().get("localScore")).isGreaterThan(50);
    }

    @Test
    void blankModelReplyFallsBackToLocalOnlyButStaysFlagged() {
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REWRITTEN), Map.of(), new StubGateway(null));

        assertThat(out.data()).containsEntry("method", "local-only")
                .containsEntry("llmNote", "模型没有返回内容");
        assertThat(out.degraded()).isTrue();
    }

    @Test
    void useLlmFalseSkipsTheCall() {
        StubGateway gateway = new StubGateway(LLM_JSON);
        // 主动关掉模型 = 只要本地口径，这是选择不是故障，不该被降级噪声淹没
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REWRITTEN, "useLlm", "false"), Map.of(), gateway);

        assertThat(gateway.last).isNull();
        assertThat(out.data()).containsEntry("method", "local-only").doesNotContainKey("llmNote");
        assertThat(out.degraded()).isFalse();
    }

    @Test
    void textsComeFromUpstreamPipeline() {
        Map<String, Object> upstream = Map.of(
                "a", Map.of("title", "原文标题", "text", SOURCE),
                "b", Map.of("title", "新标题", "body", REWRITTEN));
        NodeOutput out = run(Map.of("maxScore", 30), upstream, null);

        assertThat((int) out.data().get("sourceChars")).isEqualTo(SOURCE.trim().length());
        assertThat((int) out.data().get("generatedChars")).isEqualTo(REWRITTEN.trim().length());
        assertThat(out.data()).containsEntry("maxScore", 30);
    }

    @Test
    void missingReferenceArticleIsRejectedNotSilentlyPassed() {
        assertThatThrownBy(() -> run(Map.of("generated", REWRITTEN), Map.of("b", Map.of("body", REWRITTEN)), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("article.extract");
                });
    }

    @Test
    void promptCarriesBothArticlesInReferenceOrder() {
        StubGateway gateway = new StubGateway(LLM_JSON);
        run(Map.of("source", SOURCE, "generated", REWRITTEN), Map.of(), gateway);

        assertThat(gateway.last.system()).contains("原创度审核员");
        // 与参考项目同一约定：文章A=原文、文章B=生成文，顺序反了分数口径就变了
        assertThat(gateway.last.prompt()).contains("【文章A｜原始参考文章】").contains("【文章B｜待交付生成文章】");
        assertThat(gateway.last.prompt().indexOf("公司要求每周交周报"))
                .isLessThan(gateway.last.prompt().indexOf("例行文档之所以让人烦躁"));
    }

    @Test
    void extractJsonSlicesFencesAndPreamble() {
        assertThat(OriginalityCheckNodeExecutor.extractJson("好的：\n```json\n{\"similarityScore\": 12}\n```"))
                .isEqualTo("{\"similarityScore\": 12}");
        assertThatThrownBy(() -> OriginalityCheckNodeExecutor.extractJson("没有对象"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void riskBandsPinned() {
        assertThat(OriginalityCheckNodeExecutor.riskLevel(25)).isEqualTo("low");
        assertThat(OriginalityCheckNodeExecutor.riskLevel(26)).isEqualTo("medium");
        assertThat(OriginalityCheckNodeExecutor.riskLevel(50)).isEqualTo("medium");
        assertThat(OriginalityCheckNodeExecutor.riskLevel(51)).isEqualTo("high");
    }

    /** 全角引号被模型当成 JSON 语法用：原样解析失败后必须退回替换版，否则这条腿整次评分都白跑。 */
    @Test
    void curlyQuotesUsedAsSyntaxStillGetScored() {
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REWRITTEN), Map.of(), new StubGateway("""
                结论如下 {“similarityScore”: 40, “summary”: “结构沿用”} 供参考"""));

        assertThat(out.data()).containsEntry("llmScore", 40).containsEntry("method", "llm+local")
                .doesNotContainKey("llmNote");
        assertThat(out.degraded()).isTrue();
    }

    /**
     * 值里摘录原文用的全角引号不能被替换掉：替换会把这份本来合法的 JSON 换碎，
     * 执行 16/17 就是这样静默退回本地口径、给出误导性的 0 分。
     */
    @Test
    void curlyQuotesInsideStringValuesKeepParsing() {
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REWRITTEN), Map.of(), new StubGateway("""
                {"similarityScore": 33, "summary": "把“内耗”拆成三个开关",
                 "similarFragments": [{"fragmentA": "“被需要”是一种燃料", "fragmentB": "“有用”才让人安心", "similarity": 70}]}"""));

        assertThat(out.data()).containsEntry("llmScore", 33).containsEntry("method", "llm+local")
                .doesNotContainKey("llmNote");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> frags =
                (java.util.List<java.util.Map<String, Object>>) out.data().get("modelFragments");
        assertThat(frags).hasSize(1);
        assertThat((String) frags.get(0).get("fragmentA")).contains("“被需要”");
    }

    /** 报文压根不是 JSON 时，降级原因要带上报文头部，否则线上只能看见一个异常类名。 */
    @Test
    void unavailableModelKeepsReplyHeadInNote() {
        NodeOutput out = run(Map.of("source", SOURCE, "generated", REWRITTEN), Map.of(),
                new StubGateway("抱歉，我无法判断这两篇文章的相似度 {不完整"));

        assertThat(out.degraded()).isTrue();
        assertThat((String) out.data().get("llmNote")).contains("报文头部").contains("我无法判断");
    }

    @Test
    void contractValuesPinned() {
        OriginalityCheckNodeExecutor executor = new OriginalityCheckNodeExecutor(() -> null);
        assertThat(executor.type()).isEqualTo("copy.originality");
        assertThat(executor.timeout().toSeconds()).isEqualTo(90);
        assertThat(executor.retryPolicy().maxAttempts()).isEqualTo(1);
        assertThat(executor.hasSideEffects()).isFalse();
    }
}

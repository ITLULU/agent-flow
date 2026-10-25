package com.agentflow.nodes.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.agentflow.nodes.support.HttpJson;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 参考图风格解析：报文拼装、两种 content 形态、JSON 容忍度、失败口径。
 * 投递口用桩，不碰网络；模型真答什么格式由线上验证覆盖（本地无出网假设）。
 */
class ReferenceStyleAnalyzerTest {

    private static final String DATA_URL = "data:image/png;base64,iVBORw0KGgo=";

    private static final String CLEAN = "{\"subject\":\"俯拍平铺\",\"composition\":\"居中留白\","
            + "\"palette\":\"奶油白+陶土橘\",\"light\":\"窗光柔光\",\"texture\":\"细胶片颗粒\","
            + "\"mood\":\"松弛\",\"layout\":\"无边框\",\"antiAi\":\"桌面有划痕;光不均匀;手指入镜\","
            + "\"prompt\":\"overhead flat lay, cream and terracotta palette, soft window light, film grain\"}";

    private static ReferenceStyleAnalyzer analyzer(String reply, AtomicReference<String> body,
                                                   AtomicReference<String> url) {
        ReferenceStyleAnalyzer.JsonPoster stub = (u, b, kv) -> {
            url.set(u);
            body.set(b);
            try {
                return HttpJson.mapper().readTree(reply);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        return new ReferenceStyleAnalyzer("sk-test", "http://stub", stub);
    }

    @Test
    void postsMultimodalRequestWithInlineDataUrlAndTemplate() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> url = new AtomicReference<>();
        ReferenceStyleAnalyzer.Result r = analyzer(
                "{\"output\":{\"choices\":[{\"message\":{\"content\":" + quote(CLEAN) + "}}]}}", body, url)
                .analyze(DATA_URL, null);

        assertThat(url.get()).isEqualTo("http://stub/api/v1/services/aigc/multimodal-generation/generation");
        assertThat(body.get()).contains("\"model\":\"qwen-vl-max\"").contains(DATA_URL)
                .contains("风格配方"); // 提示词模板被带进请求
        assertThat(r.fields()).containsEntry("palette", "奶油白+陶土橘").containsEntry("antiAi", "桌面有划痕;光不均匀;手指入镜");
        assertThat(r.stylePrompt()).contains("overhead flat lay").contains("film grain");
    }

    @Test
    void contentMayBeArrayOrString() {
        AtomicReference<String> body = new AtomicReference<>();
        String arrayForm = "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"text\":" + quote(CLEAN) + "}]}}]}}";
        ReferenceStyleAnalyzer.Result r = analyzer(arrayForm, body, new AtomicReference<>())
                .analyze(DATA_URL, "qwen-vl-plus");
        assertThat(r.model()).isEqualTo("qwen-vl-plus");
        assertThat(r.fields()).containsEntry("subject", "俯拍平铺");
    }

    @Test
    void fencedAndPrefixedJsonStillParses() {
        String messy = "好的，这是结果：\n```json\n" + CLEAN + "\n```";
        ReferenceStyleAnalyzer.Result r = analyzer(
                "{\"output\":{\"choices\":[{\"message\":{\"content\":" + quote(messy) + "}}]}}",
                new AtomicReference<>(), new AtomicReference<>()).analyze(DATA_URL, null);
        assertThat(r.fields()).containsEntry("mood", "松弛");
        assertThat(r.stylePrompt()).contains("overhead flat lay");
    }

    /** 模型不按 JSON 回：整段当风格描述用，而不是让页面拿到空配方（空配方=风格丢失但看起来成功）。 */
    @Test
    void nonJsonReplyFallsBackToWholeText() {
        String prose = "这张图是暖色调胶片风，构图偏右下，光线来自左侧窗户。";
        ReferenceStyleAnalyzer.Result r = analyzer(
                "{\"output\":{\"choices\":[{\"message\":{\"content\":" + quote(prose) + "}}]}}",
                new AtomicReference<>(), new AtomicReference<>()).analyze(DATA_URL, null);
        assertThat(r.fields()).containsEntry("prompt", prose);
        assertThat(r.stylePrompt()).isEqualTo(prose);
    }

    @Test
    void missingPromptFieldComposesFromDimensions() {
        String noPrompt = "{\"subject\":\"半身人像\",\"palette\":\"低饱和\",\"antiAi\":\"发丝有杂光\"}";
        ReferenceStyleAnalyzer.Result r = analyzer(
                "{\"output\":{\"choices\":[{\"message\":{\"content\":" + quote(noPrompt) + "}}]}}",
                new AtomicReference<>(), new AtomicReference<>()).analyze(DATA_URL, null);
        assertThat(r.stylePrompt()).contains("半身人像").contains("低饱和").contains("发丝有杂光");
    }

    @Test
    void emptyReplyIsProtocolError() {
        assertThatThrownBy(() -> analyzer("{\"output\":{\"choices\":[]}}", new AtomicReference<>(),
                new AtomicReference<>()).analyze(DATA_URL, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void blankImageIsBadRequest() {
        assertThatThrownBy(() -> analyzer("{}", new AtomicReference<>(), new AtomicReference<>()).analyze(" ", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}

package com.agentflow.nodes.image;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.nodes.copy.PromptTemplates;
import com.agentflow.nodes.support.HttpJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReferenceStyleAnalyzer — 参考图 → 风格配方（qwen-vl 多模态）。
 *
 * 为什么用"看图说风格"而不是图生图模型：万相 t2i 系列只吃文字提示词，没有把参考图当风格条件的入参；
 * 而用户的诉求是"照这张图的观感，换我自己的内容"。所以先让视觉模型把风格拆成文字，
 * 再由这段文字去驱动文生图——拆出来的配方还能给用户改（前端直接编辑），比黑盒风格迁移更可控。
 *
 * 传图走 data URL（base64 内联）：本地图、浏览器上传的图都能带，省掉"先传 OSS 再给 URL"这一跳。
 * 投递端口抽成 {@link JsonPoster}，单测无需真实网络即可钉住报文拼装与解析。
 */
public class ReferenceStyleAnalyzer {

    private static final String MULTIMODAL_PATH = "/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DEFAULT_MODEL = "qwen-vl-max";

    /** 风格维度的展示顺序与中文名（LinkedHashMap：顺序要稳定，前端按它摆卡片，API 回显也按它排）。 */
    public static final Map<String, String> FIELD_LABELS = labels();

    private static Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("subject", "主体与机位");
        m.put("composition", "构图");
        m.put("palette", "配色");
        m.put("light", "光线");
        m.put("texture", "质感");
        m.put("mood", "氛围");
        m.put("layout", "版式");
        m.put("antiAi", "去 AI 味细节");
        return Map.copyOf(m); // 不变量；对外顺序由 FIELD_ORDER 决定，别按这个 Map 迭代
    }

    /** 字段顺序（解析、拼接提示词、API 回显都以此为准）。 */
    public static final List<String> FIELD_ORDER =
            List.of("subject", "composition", "palette", "light", "texture", "mood", "layout", "antiAi");

    private final String apiKey;
    private final String baseUrl;
    private final JsonPoster poster;

    public ReferenceStyleAnalyzer(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, new HttpJsonPoster());
    }

    public ReferenceStyleAnalyzer(String apiKey, String baseUrl, JsonPoster poster) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.poster = poster;
    }

    /** @param imageDataUrl data:image/...;base64,xxxx；@param model 传空则用 qwen-vl-max */
    public Result analyze(String imageDataUrl, String model) {
        if (imageDataUrl == null || imageDataUrl.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "参考图不能为空（需 data:image/...;base64 形式）");
        }
        String usedModel = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        JsonNode json = poster.post(baseUrl + MULTIMODAL_PATH, body(imageDataUrl, usedModel),
                "Authorization", "Bearer " + apiKey);
        String reply = replyText(json, usedModel);
        Map<String, String> fields = parseFields(reply);
        String stylePrompt = fields.getOrDefault("prompt", "").trim();
        if (stylePrompt.isEmpty()) {
            // 模型没按格式给 prompt 字段时，把已拆出的维度拼成提示词，仍比整段报文干净
            stylePrompt = composeFromFields(fields);
        }
        return new Result(usedModel, fields, stylePrompt, reply);
    }

    private String body(String imageDataUrl, String model) {
        ObjectMapper mapper = HttpJson.mapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject().put("image", imageDataUrl);
        content.addObject().put("text", PromptTemplates.load("image/reference-analysis.txt"));
        return root.toString();
    }

    /** 多模态端点的 content 既可能是字符串也可能是 [{text:...}] 数组，两种都收。 */
    private static String replyText(JsonNode json, String model) {
        JsonNode content = json.path("output").path("choices").path(0).path("message").path("content");
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                String t = part.path("text").asText(part.asText(""));
                if (!t.isBlank()) {
                    sb.append(t);
                }
            }
        } else if (content.isTextual()) {
            sb.append(content.asText());
        }
        if (sb.isEmpty()) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR,
                    "参考图解析报文里没有文本内容: " + HttpJson.brief(json.toString()));
        }
        String text = sb.toString().trim();
        if (text.contains("InvalidApiKey") || json.path("code").asText("").startsWith("Invalid")) {
            throw new ApiException(ErrorCode.UPSTREAM_REJECTED, "DashScope 拒绝了请求（检查 BAILIAN/DASHSCOPE key）: "
                    + json.path("code").asText(""));
        }
        return text;
    }

    /** 容忍 ```围栏与前言：截首个 { 到末个 } 再解析；解析不了就退化成"整段当风格描述"。 */
    static Map<String, String> parseFields(String reply) {
        Map<String, String> out = new LinkedHashMap<>();
        String json = sliceJsonObject(reply);
        if (json == null) {
            out.put("prompt", reply.replaceAll("[\\r\\n]+", " ").trim());
            return out;
        }
        JsonNode node;
        try {
            node = HttpJson.mapper().readTree(json);
        } catch (Exception e) {
            out.put("prompt", reply.replaceAll("[\\r\\n]+", " ").trim());
            return out;
        }
        for (String key : FIELD_ORDER) {
            String v = node.path(key).asText("").trim();
            if (!v.isEmpty()) {
                out.put(key, v);
            }
        }
        String prompt = node.path("prompt").asText("").trim();
        if (!prompt.isEmpty()) {
            out.put("prompt", prompt);
        }
        return out;
    }

    private static String sliceJsonObject(String reply) {
        String s = reply == null ? "" : reply.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return start >= 0 && end > start ? s.substring(start, end + 1) : null;
    }

    private static String composeFromFields(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (String key : FIELD_ORDER) {
            String v = fields.get(key);
            if (v != null && !v.isBlank()) {
                sb.append(v.replaceAll("[\\r\\n]+", " ")).append(". ");
            }
        }
        return sb.toString().trim();
    }

    /** 投递端口：真实实现走 HttpJson，单测给桩。 */
    @FunctionalInterface
    public interface JsonPoster {
        JsonNode post(String url, String jsonBody, String... headerKv);
    }

    private static final class HttpJsonPoster implements JsonPoster {
        private final HttpJson http = new HttpJson(java.time.Duration.ofSeconds(60));

        @Override
        public JsonNode post(String url, String jsonBody, String... headerKv) {
            return http.postJsonAndGet(url, jsonBody, headerKv);
        }
    }

    /** fields=结构化风格维度（前端可编辑），stylePrompt=喂给文生图的那段话。 */
    public record Result(String model, Map<String, String> fields, String stylePrompt, String raw) {
    }
}

package com.agentflow.server.web;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.nodes.image.CoverImageNodeExecutor;
import com.agentflow.nodes.image.ReferenceStyleAnalyzer;
import com.agentflow.server.persistence.JdbcContentStore;
import com.agentflow.server.security.EndpointSensitivity;
import com.agentflow.server.security.PrincipalContext;
import com.agentflow.server.security.Sensitivity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 独立生图工作台 API——不走工作流，一次请求出一组图。
 *
 * 为什么复用 {@link CoverImageNodeExecutor} 而不是重写一套生图逻辑：
 * 场景比例、风格预设、反 AI 负向词、四宫格拼接的口径必须和画布节点**完全一致**，
 * 否则用户在页面里调好的图，搬到流水线里就跑不出同样的结果。这里只是把节点从"引擎调度"
 * 挪到"HTTP 直调"，生图语义单一来源。
 *
 * jobId 用**负数**：内容库暂存目录是 {.staging}/{executionId}，而真实 executionId 来自 DB 序列（正数），
 * 取负号即可保证独立生图的临时图永远不会和工作流执行的暂存区撞目录，也就不会被 attachStaged 搬走或漏清。
 *
 * 落盘依赖内容库；未配置 library-dir 时节点只回远端 DashScope URL，页面仍能预览（URL 24 小时过期）。
 * 文件回读是鉴权端点，前端用 fetch+blob 取图（与成品下载同一口径），不用 <img src> 直连。
 */
@RestController
@RequestMapping("/api/image/studio")
public class ImageStudioController {

    private static final List<String> SCENES = List.of("xhs-cover", "xhs-body", "wechat-cover", "wechat-chapter");
    private static final List<String> STYLES = List.of("film", "street", "minimal", "warm", "dark", "vintage");

    /** 负数 jobId 发号器：初值取当前毫秒的负数，重启后可能复用目录名，覆盖写无害。 */
    private static final AtomicLong JOB_SEQ = new AtomicLong(-System.currentTimeMillis());

    /** 参考图 base64 上限（约 6MB 原图）：内联传图没有请求体大小闸门，这里先挡住，防 OOM。 */
    private static final int MAX_DATA_URL_CHARS = 8_000_000;

    private final CoverImageNodeExecutor coverImage;
    private final JdbcContentStore contentStore;
    private final ObjectMapper mapper;
    private final ReferenceStyleAnalyzer styleAnalyzer;

    public ImageStudioController(CoverImageNodeExecutor coverImage, JdbcContentStore contentStore,
                                 ObjectMapper mapper, ObjectProvider<ReferenceStyleAnalyzer> styleAnalyzer) {
        this.coverImage = coverImage;
        this.contentStore = contentStore;
        this.mapper = mapper;
        this.styleAnalyzer = styleAnalyzer.getIfAvailable();
    }

    /**
     * 参考图 → 风格配方。前端把这张卡当"可编辑的中间产物"：解析出来的八个维度用户可以改，
     * 改完再拿去生图——黑盒风格迁移做不到这一步。
     */
    @EndpointSensitivity(Sensitivity.USER)
    @PostMapping("/analyze-reference")
    public ApiResponse<Map<String, Object>> analyzeReference(@RequestBody String rawJson) {
        JsonNode req = parse(rawJson);
        String image = req.path("image").asText("");
        Map<String, Object> out = styleOf(image, text(req, "model"));
        return ApiResponse.ok(out);
    }

    /** fields 按 FIELD_ORDER 排（前端直接迭代渲染），并回一份拼好的 stylePrompt 供"跳过编辑"的调用方使用。 */
    private Map<String, Object> styleOf(String imageDataUrl, String model) {
        if (styleAnalyzer == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "参考图解析需要生图凭证（agentflow.nodes.image.api-key / DASHSCOPE_API_KEY），当前未配置");
        }
        if (imageDataUrl.length() > MAX_DATA_URL_CHARS) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "参考图过大（base64 上限 " + (MAX_DATA_URL_CHARS / 1_000_000) + "MB），请先压缩到 2MB 以内");
        }
        ReferenceStyleAnalyzer.Result result = styleAnalyzer.analyze(imageDataUrl, model);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String key : ReferenceStyleAnalyzer.FIELD_ORDER) {
            String v = result.fields().get(key);
            if (v != null && !v.isBlank()) {
                fields.add(Map.of("key", key, "label", ReferenceStyleAnalyzer.FIELD_LABELS.get(key), "value", v));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", result.model());
        out.put("fields", fields);
        out.put("stylePrompt", result.stylePrompt());
        return out;
    }

    @EndpointSensitivity(Sensitivity.USER)
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestBody String rawJson) {
        JsonNode req = parse(rawJson);
        String scene = text(req, "scene");
        if (!scene.isEmpty() && !SCENES.contains(scene)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "scene 只支持 " + String.join("/", SCENES));
        }
        String style = text(req, "style");
        if (!style.isEmpty() && !STYLES.contains(style)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "style 只支持 " + String.join("/", STYLES));
        }
        String topic = text(req, "topic");
        String prompt = text(req, "prompt");
        if (topic.isEmpty() && prompt.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "topic 与 prompt 至少要填一个");
        }
        String collage = text(req, "collage");
        if (!collage.isEmpty() && !"2x2".equals(collage)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "collage 目前只支持 2x2");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (!scene.isEmpty()) {
            params.put("scene", scene);
        }
        // raw=true 时提示词整段自己写（配方/场景模板不再叠加，用于"我就要这句话"）；
        // 默认走 topic：内容 + 参考配方 + 场景骨架 + 风格词由节点拼合，这才是"换内容、留风格"的主路径。
        if (req.path("raw").asBoolean(false) && !prompt.isEmpty()) {
            params.put("prompt", prompt);
        } else {
            params.put("topic", prompt.isEmpty() ? topic : (topic.isEmpty() ? prompt : topic + " " + prompt));
        }
        if (!style.isEmpty()) {
            params.put("promptStyle", style);
        }
        putIfPresent(params, "referenceStyle", referenceStyleOf(req));
        putIfPresent(params, "negativePrompt", text(req, "negativePrompt"));
        putIfPresent(params, "ratio", text(req, "ratio"));
        putIfPresent(params, "model", text(req, "model"));
        if ("2x2".equals(collage)) {
            params.put("collage", "2x2");
        }
        params.put("n", nOf(req));
        params.put("save", true);

        long jobId = JOB_SEQ.decrementAndGet();
        ExecutionContext ctx = new ExecutionContext(PrincipalContext.get().uid() + "/studio/" + jobId, 0L, jobId);
        NodeOutput out = coverImage.execute(new NodeInput(params, Map.of()), ctx);
        return ApiResponse.ok(toView(jobId, scene, out));
    }

    /** 参考风格文本优先；没给但给了参考图（data URL）就现场解析一次——页面可以"一步出图"，也可以先解析再改。 */
    private String referenceStyleOf(JsonNode req) {
        String style = text(req, "referenceStyle");
        if (!style.isEmpty()) {
            return style;
        }
        String image = req.path("referenceImage").asText("");
        if (image.isBlank()) {
            return "";
        }
        return String.valueOf(styleOf(image, text(req, "visionModel")).get("stylePrompt"));
    }

    /** 回吐独立生图的暂存文件。目录由 jobId 定位，文件名再过一次穿越校验，只允许读 .staging/{jobId} 内的文件。 */
    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping("/file")
    public ResponseEntity<FileSystemResource> file(@RequestParam long jobId, @RequestParam String name) {
        if (jobId >= 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "jobId 必须是独立生图返回的负数 id");
        }
        List<String> allowed = contentStore.stagedFiles(jobId);
        if (!allowed.contains(name)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "文件不存在: " + name);
        }
        Path target = contentStore.stagedFile(jobId, name); // 白名单之外再兜一层 basename 清洗 + 穿越校验
        if (!Files.isReadable(target)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "文件不存在: " + name);
        }
        return ResponseEntity.ok()
                .contentType(imageType(name))
                .body(new FileSystemResource(target));
    }

    private static Map<String, Object> toView(long jobId, String scene, NodeOutput out) {
        Map<String, Object> data = out.data();
        List<Map<String, Object>> images = new ArrayList<>();
        for (Object o : asList(data.get("images"))) {
            if (!(o instanceof Map<?, ?> img)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", img.get("fileName"));
            item.put("url", img.get("url"));
            item.put("ratio", img.get("ratio"));
            item.put("size", img.get("size"));
            item.put("bytes", img.get("bytes"));
            item.put("localPath", img.get("localPath"));
            Object fn = img.get("fileName");
            item.put("downloadUrl", fn == null ? null : fileUrl(jobId, String.valueOf(fn)));
            images.add(item);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", jobId);
        view.put("scene", scene);
        view.put("prompt", data.get("prompt"));
        view.put("negativePrompt", data.get("negativePrompt"));
        view.put("ratio", data.get("ratio"));
        view.put("size", data.get("size"));
        view.put("model", data.get("model"));
        view.put("taskId", data.get("taskId"));
        view.put("costMs", data.get("costMs"));
        view.put("stored", data.get("stored"));
        view.put("count", images.size());
        view.put("images", images);
        Object collage = data.get("collage");
        if (collage instanceof Map<?, ?> c) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("format", c.get("format"));
            cm.put("ratio", c.get("ratio"));
            cm.put("bytes", c.get("bytes"));
            cm.put("localPath", c.get("localPath"));
            Object fn = fileNameOf(c.get("localPath"));
            cm.put("fileName", fn);
            cm.put("downloadUrl", fn == null ? null : fileUrl(jobId, String.valueOf(fn)));
            view.put("collage", cm);
        } else {
            view.put("collage", null);
        }
        return view;
    }

    private static String fileUrl(long jobId, String fileName) {
        return "/api/image/studio/file?jobId=" + jobId + "&name=" + fileName;
    }

    private static Object fileNameOf(Object localPath) {
        if (localPath == null) {
            return null;
        }
        String s = String.valueOf(localPath).replace('\\', '/');
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    private static List<?> asList(Object v) {
        return v instanceof List<?> list ? list : List.of();
    }

    private static MediaType imageType(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (n.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_PNG;
    }

    private static int nOf(JsonNode req) {
        int n = req.path("n").asInt(1);
        return Math.max(1, Math.min(n, 4));
    }

    private static void putIfPresent(Map<String, Object> params, String key, String value) {
        if (!value.isEmpty()) {
            params.put(key, value);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isTextual() ? v.asText().trim() : "";
    }

    private JsonNode parse(String rawJson) {
        try {
            return mapper.readTree(rawJson);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "JSON 解析失败: " + e.getClass().getSimpleName());
        }
    }
}

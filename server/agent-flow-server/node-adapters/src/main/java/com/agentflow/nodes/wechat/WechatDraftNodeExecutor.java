package com.agentflow.nodes.wechat;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.engine.spi.ExecutionContext;
import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.engine.spi.NodeInput;
import com.agentflow.engine.spi.NodeOutput;
import com.agentflow.engine.spi.RetryPolicy;
import com.agentflow.nodes.spi.CredentialProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * wechat.draft — 微信公众号草稿箱发布节点（token → 封面素材 → draft/add，复刻 n8n 版验证过的 API 序列）。
 *
 * params: title / content / digest / thumbUrl 都可显式给，缺省按上游创作产物回退：
 *         title←copy.write.title，content←copy.write.body（纯文本自动转段落 HTML），
 *         digest←copy.write.digest，thumbUrl←image.cover.images[0].url（也兼容 image.wanxiang 的 imageUrl）
 *         author(仅显式参数)
 *         credentialId(可选，P3 凭证库引用；命中则覆盖全局 env 凭证)
 * 输出: draftMediaId / title / hasThumb
 *
 * 四件套口径：
 * - 超时：引擎层 60s；微信 API 正常 <3s，超时即异常态。
 * - 重试：2 次；token 失效(errcode 40001/42001)会先清缓存再抛，重试自然走新 token。
 *           例外：引擎级超时（draft/add 已发出但结果不可知）后不再重试——本节点已声明 hasSideEffects，
 *           重试等于同一篇稿子发两次（评审 P6 #7，由 WechatDraftTimeoutTest 用真 HTTP 桩钉住）。
 * - 异常：errcode 40013/40125 等凭证错误→A0400（重试无意义）；-1 系统繁忙→C0002；其余非零→C0003；连接/超时同三分类。
 * - 降级：不覆盖 degrade——发半截/发错内容比不发更糟，失败必须显性 FAILED 让人工介入。
 *
 * token 缓存：实例级 ConcurrentHashMap（key=appId，多公众号凭证不串号），提前 5 分钟过期刷新；公众号 token 有 2000/日配额，必须缓存。
 * 凭证：优先 credentialId → CredentialProvider 端口（P3 加密凭证库），缺省全局 env
 *       （WX_API_APPID/WX_API_SECRET，兼容旧名 WECHAT_APPID/WECHAT_SECRET，见 application.yml nodes.wechat）。
 */
public class WechatDraftNodeExecutor implements NodeExecutor {

    public static final String TYPE = "wechat.draft";
    private static final Duration TOKEN_EARLY_REFRESH = Duration.ofMinutes(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final String appId;
    private final String appSecret;
    private final String baseUrl;
    private final Duration callTimeout;
    private volatile CredentialProvider credentialProvider;
    /** key=appId：全局单例节点服务多用户多公众号，token 按账号分键，绝不能共用单槽缓存。 */
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    private record CachedToken(String value, long expiresAtEpochMs) {
        boolean fresh(long now) {
            return now < expiresAtEpochMs;
        }
    }

    public WechatDraftNodeExecutor(String appId, String appSecret) {
        this(appId, appSecret, "https://api.weixin.qq.com", Duration.ofSeconds(15));
    }

    /** baseUrl/callTimeout 为测试桩与代理场景留口子。 */
    public WechatDraftNodeExecutor(String appId, String appSecret, String baseUrl, Duration callTimeout) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.baseUrl = baseUrl;
        this.callTimeout = callTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** server 装配时注入凭证端口；null=只能用全局 env 凭证（本地/存量行为）。 */
    public void setCredentialProvider(CredentialProvider credentialProvider) {
        this.credentialProvider = credentialProvider;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(60);
    }

    @Override
    public boolean hasSideEffects() {
        return true; // 真实创建草稿/付费出图：引擎超时后无法判定对端是否已收到，重试=重复发布/重复扣费
    }

    @Override
    public RetryPolicy retryPolicy() {
        return new RetryPolicy(2, 1500L);
    }

    @Override
    public NodeOutput execute(NodeInput input, ExecutionContext ctx) {
        String[] creds = credsFor(input, ctx);
        String appId = creds[0];
        String appSecret = creds[1];
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "公众号发布未配置（缺少 WX_API_APPID/WX_API_SECRET（旧名 WECHAT_APPID/WECHAT_SECRET 也可）或 credentialId）");
        }
        String title = firstNonBlank(input.paramAsString("title", null), upstreamString(input, "title"));
        if (title.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "wechat.draft 缺少标题：请传 title 参数，或把 copy.write / article.extract 作为上游");
        }
        String content = resolveContent(input);
        if (content.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "wechat.draft 无正文：content 参数缺失且上游无 body/text/content 输出");
        }

        String token = accessToken(appId, appSecret);
        String thumbMediaId = null;
        String thumbUrl = resolveThumbUrl(input);
        if (!thumbUrl.isBlank()) {
            byte[] image = download(thumbUrl);
            thumbMediaId = uploadThumbMaterial(appId, token, image, "cover.jpg");
        }

        String draftMediaId = addDraft(appId, token, title, content,
                input.paramAsString("author", ""),
                firstNonBlank(input.paramAsString("digest", null), upstreamString(input, "digest")),
                thumbMediaId);

        Map<String, Object> data = new HashMap<>();
        data.put("draftMediaId", draftMediaId);
        data.put("title", title);
        data.put("hasThumb", thumbMediaId != null);
        return NodeOutput.ok(data);
    }

    // ---- 内部流程 ----

    /**
     * credentialId 参数（数字或数字串）→ 凭证端口；未带参数回退全局 env 凭证。任何解析失败都是 A0400 不可重试。
     * 端口要 workflowId：凭证归属只认"这条工作流的归属者"，别人 DSL 里填你的 id 也解不出（评审 P6 #1）。
     */
    private String[] credsFor(NodeInput input, ExecutionContext ctx) {
        Object raw = input.params().get("credentialId");
        if (raw == null) {
            return new String[]{appId, appSecret};
        }
        long credentialId;
        try {
            credentialId = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "credentialId 需为数字，实际: " + raw);
        }
        CredentialProvider provider = credentialProvider;
        if (provider == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "请求了 credentialId=" + credentialId + " 但凭证库不可用（未配置 AGENTFLOW_MASTER_KEY）");
        }
        Map<String, String> secrets = provider.resolve(credentialId, ctx.workflowId())
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST, "凭证不存在或无法解密: credentialId=" + credentialId));
        return new String[]{
                secrets.getOrDefault("app_id", appId),
                secrets.getOrDefault("app_secret", appSecret),
        };
    }

    private String resolveContent(NodeInput input) {
        String content = input.paramAsString("content", null);
        if (content != null && !content.isBlank()) {
            return toHtml(content);
        }
        // 流水线回退：body（copy.write 成稿）→ content（llm.chat）→ text（http.collect / article.extract 原文）。
        // 键在外层、上游在内层：反过来的话，节点 id 字典序靠前的采集节点会先用它的原文 text 命中，
        // 外链改写链路就会把原文正文当新稿发进草稿箱（2026-10-25 17:33 实测 draftMediaId 里就是原文）。
        String found = upstreamValue(input, "body", "content", "text");
        return found.isEmpty() ? "" : toHtml(found);
    }

    private String resolveThumbUrl(NodeInput input) {
        String thumb = input.paramAsString("thumbUrl", null);
        if (thumb != null && !thumb.isBlank()) {
            return thumb;
        }
        for (Map<String, Object> out : orderedUpstream(input)) {
            Object u = out.get("imageUrl");
            if (u != null && !String.valueOf(u).isBlank()) {
                return String.valueOf(u);
            }
            String fromCover = firstImageUrl(out.get("images"));
            if (fromCover != null) {
                return fromCover;
            }
        }
        return "";
    }

    /** image.cover 的 images[{url,...}] 取第一张远端地址——微信封面素材要先把它下载下来。 */
    private static String firstImageUrl(Object imagesValue) {
        if (!(imagesValue instanceof List<?> images)) {
            return null;
        }
        for (Object item : images) {
            if (item instanceof Map<?, ?> map) {
                Object url = map.get("url");
                if (url != null && !String.valueOf(url).isBlank()) {
                    return String.valueOf(url);
                }
            }
        }
        return null;
    }

    /**
     * 微信草稿正文必须是 HTML：创作节点产出纯文本，原样提交会被微信压成一整段（换行全丢），
     * 所以无标签时按段落切分并转义。已带 p/section/br 标签的内容视为用户自己的排版，原样透传。
     */
    private static String toHtml(String raw) {
        String text = raw.trim();
        if (text.contains("<p") || text.contains("<section") || text.contains("<br")) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (String para : text.split("\\n+")) {
            String p = para.trim();
            if (!p.isEmpty()) {
                sb.append("<p>").append(escapeHtml(p)).append("</p>");
            }
        }
        return sb.isEmpty() ? "<p>" + escapeHtml(text) + "</p>" : sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String upstreamString(NodeInput input, String key) {
        return upstreamValue(input, key);
    }

    /**
     * 按"键的优先级"取上游值：先问创作产物（带 body 的那份输出 = copy.write 成稿），再按 id 字典序问其余上游。
     * 创作产物优先是必须的：采集节点也带 title/text，仅按 id 排序会让原文盖掉新稿。
     */
    private static String upstreamValue(NodeInput input, String... keys) {
        List<Map<String, Object>> upstreams = orderedUpstream(input);
        Map<String, Object> creation = upstreams.stream()
                .filter(out -> nonBlankValue(out, "body") != null)
                .findFirst()
                .orElse(Map.of());
        for (String key : keys) {
            String fromCreation = nonBlankValue(creation, key);
            if (fromCreation != null) {
                return fromCreation;
            }
            for (Map<String, Object> out : upstreams) {
                if (out == creation) {
                    continue;
                }
                String v = nonBlankValue(out, key);
                if (v != null) {
                    return v;
                }
            }
        }
        return "";
    }

    private static String nonBlankValue(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /** 上游按节点 id 排序保证多上游时取数确定性（与 DagValidator 字典序同一哲学）。 */
    private static List<Map<String, Object>> orderedUpstream(NodeInput input) {
        return input.upstream().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> e.getValue() instanceof Map)
                .map(e -> asMap(e.getValue()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return (Map<String, Object>) v;
    }

    private String accessToken(String appId, String appSecret) {
        CachedToken cached = tokenCache.get(appId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.fresh(now)) {
            return cached.value();
        }
        String path = "/cgi-bin/token?grant_type=client_credential&appid="
                + URLEncoder.encode(appId, StandardCharsets.UTF_8) + "&secret="
                + URLEncoder.encode(appSecret, StandardCharsets.UTF_8);
        JsonNode json = getJson(path);
        int errcode = json.path("errcode").asInt(0);
        if (errcode != 0 || json.path("access_token").isMissingNode()) {
            throw mapWechatError("token", errcode, json.path("errmsg").asText(""));
        }
        long expiresInSec = json.path("expires_in").asLong(7200);
        tokenCache.put(appId, new CachedToken(json.path("access_token").asText(),
                now + (expiresInSec - TOKEN_EARLY_REFRESH.toSeconds()) * 1000));
        return json.path("access_token").asText();
    }

    private byte[] download(String url) {
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(safeUri(url)).GET().timeout(callTimeout).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (ApiException e) {
            throw e; // safeUri 已分类为 A0400，不得被兵底重映射
        } catch (RuntimeException | IOException | InterruptedException e) {
            throw mapIo("封面图下载", e);
        }
        if (resp.statusCode() / 100 != 2 || resp.body().length == 0) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, "封面图下载失败 HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private String uploadThumbMaterial(String appId, String token, byte[] image, String filename) {
        String boundary = "----agentflow" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, filename, image);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(safeUri(baseUrl + "/cgi-bin/material/add_material?access_token="
                            + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&type=image"))
                    .timeout(callTimeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "素材上传URL不合法: " + e.getMessage());
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException | IOException | InterruptedException e) {
            throw mapIo("素材上传", e);
        }
        JsonNode json = parseJson("素材上传", resp.body());
        int errcode = json.path("errcode").asInt(0);
        String mediaId = json.path("media_id").asText("");
        if (errcode != 0 || mediaId.isBlank()) {
            if (errcode == 40001 || errcode == 42001) {
                tokenCache.remove(appId); // 永久素材接口同样受 token 过期影响（只清本账号）
            }
            // 素材上传失败多为凭证/配额问题
            throw mapWechatError("素材上传", errcode, json.path("errmsg").asText(""));
        }
        return mediaId;
    }

    private String addDraft(String appId, String token, String title, String content, String author, String digest, String thumbMediaId) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode articles = body.putArray("articles");
        ObjectNode article = articles.addObject();
        article.put("title", title);
        article.put("author", author);
        article.put("digest", digest);
        article.put("content", content);
        article.put("need_open_comment", 0);
        if (thumbMediaId != null) {
            article.put("thumb_media_id", thumbMediaId);
        }
        String path = "/cgi-bin/draft/add?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        JsonNode json = postJson(path, body.toString());
        int errcode = json.path("errcode").asInt(0);
        String mediaId = json.path("media_id").asText("");
        if (errcode != 0 || mediaId.isBlank()) {
            if (errcode == 40001 || errcode == 42001) {
                tokenCache.remove(appId); // token 失效：清本账号缓存，让引擎重试自然走新 token
            }
            throw mapWechatError("draft/add", errcode, json.path("errmsg").asText(""));
        }
        return mediaId;
    }

    /** 微信特色：错误也返回 HTTP 200，errcode 藏在 body——必须逐码分类，禁止笼统当协议错。 */
    private ApiException mapWechatError(String stage, int errcode, String errmsg) {
        String detail = "微信 " + stage + " errcode=" + errcode + " errmsg=" + errmsg;
        return switch (errcode) {
            case 40013, 40125, 41004 -> new ApiException(ErrorCode.BAD_REQUEST, detail + "（AppID/AppSecret 配置错误，重试无意义）");
            case -1 -> new ApiException(ErrorCode.UPSTREAM_TIMEOUT, detail + "（微信系统繁忙，可重试）");
            case 40001, 42001 -> new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, detail + "（token 过期，已清缓存待重试）");
            case 45009 -> new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, detail + "（接口调用超配额）");
            default -> new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, detail);
        };
    }

    // ---- HTTP 基础设施（异常映射与 http.collect 同一三分类口径） ----

    private JsonNode getJson(String path) {
        return sendJson(HttpRequest.newBuilder(safeUri(baseUrl + path)).GET(), "GET " + path);
    }

    private JsonNode postJson(String path, String body) {
        return sendJson(HttpRequest.newBuilder(safeUri(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), "POST " + path);
    }

    private JsonNode sendJson(HttpRequest.Builder builder, String label) {
        HttpResponse<String> resp;
        try {
            resp = http.send(builder.timeout(callTimeout).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException | IOException | InterruptedException e) {
            throw mapIo(label, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, label + " HTTP " + resp.statusCode());
        }
        return parseJson(label, resp.body());
    }

    private JsonNode parseJson(String label, String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.UPSTREAM_PROTOCOL_ERROR, label + " 响应非 JSON: " + briefText(body));
        }
    }

    private static ApiException mapIo(String label, Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new ApiException(ErrorCode.UPSTREAM_TIMEOUT, label + " 被中断");
        }
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException || t instanceof UnknownHostException) {
                return new ApiException(ErrorCode.UPSTREAM_REJECTED, label + " 连接失败: " + t.getMessage());
            }
            if (t instanceof HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
                return new ApiException(ErrorCode.UPSTREAM_TIMEOUT, label + " 超时: " + t.getMessage());
            }
        }
        return new ApiException(ErrorCode.UPSTREAM_REJECTED, label + " IO异常: " + e.getMessage());
    }

    /** 非法 URL 是配置问题，必须转 A0400，禁止 IllegalArgumentException 裸奔到引擎。 */
    private static URI safeUri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "URL 不合法: " + url);
        }
    }

    private static byte[] multipartBody(String boundary, String filename, byte[] file) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"media\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[headBytes.length + file.length + tailBytes.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(file, 0, out, headBytes.length, file.length);
        System.arraycopy(tailBytes, 0, out, headBytes.length + file.length, tailBytes.length);
        return out;
    }

    private static String briefText(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }
}

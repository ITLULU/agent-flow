package com.agentflow.server.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS SigV4 请求签名（纯 JDK 实现，只为一个 PUT/GET 就引 AWS SDK 不值当，
 * 也与本项目"JWT/AES-GCM 用 JDK 手写、不引重型依赖"的既有口径一致）。
 *
 * 覆盖的是 S3 兼容存储（MinIO / 阿里云 OSS S3 端点 / AWS）实际校验的那条路径：
 * 规范请求 = METHOD\nURI\nQUERY\nHEADERS\nSIGNED_HEADERS\nHASHED_PAYLOAD，
 * 待签串 = ALGORITHM\nts\nscope\nhash(规范请求)，签名密钥逐层 HMAC。
 *
 * 只做"生成头"这一件事，不碰网络——这样签名口径能被单测钉死，
 * 真实端点是否接受由 E2E/实机验证（错误实现会立刻 403 SignatureDoesNotMatch，不会静默通过）。
 */
public final class SigV4 {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private SigV4() {
    }

    /**
     * 返回要附加的请求头（含 host、x-amz-date、x-amz-content-sha256、Authorization）。
     *
     * @param query 已按 wire 形式拼好的查询串（如 "marker=a%2Fb"），无查询传空串；内部会重新规范化排序
     */
    public static Map<String, String> sign(String method, String host, String path, String query,
            Map<String, String> extraHeaders, byte[] payload, String accessKey, String secretKey,
            String region, Instant when) {
        String amzDate = AMZ_DATE.format(when);
        String dateStamp = DATE_STAMP.format(when);
        String payloadHash = hex(sha256(payload == null ? new byte[0] : payload));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", host);
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", payloadHash);
        if (extraHeaders != null) {
            extraHeaders.forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.trim()));
        }
        List<String> signedNames = headers.keySet().stream().sorted().toList();

        StringBuilder canonicalHeaders = new StringBuilder();
        for (String name : signedNames) {
            canonicalHeaders.append(name).append(':').append(headers.get(name)).append('\n');
        }
        String canonicalRequest = method.toUpperCase(Locale.ROOT) + "\n"
                + canonicalUri(path) + "\n"
                + canonicalQuery(query) + "\n"
                + canonicalHeaders + "\n"
                + String.join(";", signedNames) + "\n"
                + payloadHash;

        String scope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        String signature = hex(hmac(signingKey(secretKey, dateStamp, region), stringToSign));

        Map<String, String> out = new LinkedHashMap<>();
        out.put("X-Amz-Date", amzDate);
        out.put("X-Amz-Content-Sha256", payloadHash);
        out.put("Authorization", ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + String.join(";", signedNames)
                + ", Signature=" + signature);
        return out;
    }

    /**
     * 逐段编码，保留 '/'（S3 的 key 分隔符不能被编码成 %2F）。
     *
     * 调用方必须用**同一个原始路径**同时算出规范路径与线上路径：S3 校验签名时会把收到的路径解回原键再编码一次，
     * 所以"对已编码的串再编码"（%20 → %2520）是这类实现最常见的静默 403 来源，这里刻意把它收敛成一个函数。
     */
    public static String canonicalUri(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return "/";
        }
        String[] segments = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(rfc3986(segments[i]));
        }
        return sb.toString();
    }

    /** 排序 + 编码键值；S3 要求查询串按字典序规范化。 */
    static String canonicalQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            pairs.add(rfc3986(name) + "=" + rfc3986(value));
        }
        pairs.sort(String::compareTo);
        return String.join("&", pairs);
    }

    /** AWS 的 URI 编码规则：不保留 '+'（空格编成 %20），下划线与波浪号保留。 */
    private static String rfc3986(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static byte[] signingKey(String secretKey, String dateStamp, String region) {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, SERVICE);
        return hmac(kService, "aws4_request");
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用: " + e.getClass().getSimpleName(), e);
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用: " + e.getClass().getSimpleName(), e);
        }
    }

    /** 清单与校验都用这个十六进制口径，避免各处手写小写/补齐规则不一致。 */
    public static String sha256Hex(byte[] data) {
        return hex(sha256(data));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

package com.agentflow.common.trace;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * traceId 唯一工具：MDC key 与生成规则收敛在这里，禁止散点生成。
 * 四金指标中 RT 统计口径 = 单请求进入 TraceIdFilter 到写响应的耗时（唯一定义，见 server-application 过滤器）。
 */
public final class TraceIdUtils {

    /** MDC 与日志 pattern、错误响应体共用的唯一 key。 */
    public static final String MDC_KEY = "traceId";

    /** 请求/响应透传头。 */
    public static final String HEADER = "X-Trace-Id";

    private TraceIdUtils() {
    }

    /** 生成 32 位小写十六进制 traceId。 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void put(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /** 校验外部传入的 traceId 是否可安全透传（防日志注入：只允许十六进制，长度 ≤ 64）。 */
    public static boolean isValid(String candidate) {
        return candidate != null
                && !candidate.isBlank()
                && candidate.length() <= 64
                && candidate.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }
}

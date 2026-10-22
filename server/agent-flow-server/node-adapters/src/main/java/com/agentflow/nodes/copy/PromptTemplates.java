package com.agentflow.nodes.copy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词模板加载（classpath: prompts/*.txt）。
 * 模板放资源文件而非硬编码在 Java 里：改文风属于高频调优，不该每次动代码、跑全量测试。
 */
public final class PromptTemplates {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptTemplates() {
    }

    public static String load(String name) {
        return CACHE.computeIfAbsent(name, PromptTemplates::read);
    }

    private static String read(String name) {
        String path = "prompts/" + name;
        try (InputStream in = PromptTemplates.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new ApiException(ErrorCode.INTERNAL, "提示词模板缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "提示词模板读取失败: " + path + " (" + e.getMessage() + ")");
        }
    }
}

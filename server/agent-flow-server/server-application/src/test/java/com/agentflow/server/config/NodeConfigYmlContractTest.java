package com.agentflow.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.nodes.image.DashScopeImageClient;
import com.agentflow.nodes.llm.SpringAiLlmGateway;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * yml ↔ 代码 的一致性防线（"三处一致：yml 默认 = 代码默认 = 文档声称"里最容易漂的那一处）。
 *
 * 1. **容器 secret 垫片的变量名不能从占位符链里消失**。`deploy/docker-entrypoint.sh` 把 compose 的
 *    `AI_API_KEY_FILE` 落回成环境变量 `AI_API_KEY`；本地调试若把 `llm.api-key`/`image.api-key` 的链改成
 *    只读 `AI_API_KEY1`/`AI_CHAT_API_KEY`，本地一切正常、容器起来 key 却是空，而且要到节点执行才以
 *    A0400 露出来。这条断言把它挡在提交前。
 * 2. **默认 host 必须等于代码常量且不带 path**。DashScopeApi 的协议 path 固定
 *    （实测见 `SpringAiLlmGatewayTest.configuredBaseUrlReplacesRequestHostAndIsReported`），
 *    base-url 写成 `…/compatible-mode` 会拼出 404 地址。
 */
class NodeConfigYmlContractTest {

    private static String yml() throws IOException {
        try (InputStream in = NodeConfigYmlContractTest.class.getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml 必须在 classpath 上").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 取 `agentflow.nodes.<block>` 下某个键的值（按缩进界定块范围，避免拿注释或同名键）。
     * 找不到就抛错——"配置项被删掉"也该让测试红，而不是静默跳过。
     */
    private static String valueInBlock(String text, String block, String key) {
        String wantedKey = key + ":";
        int blockIndent = -1;
        boolean inBlock = false;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int indent = raw.indexOf(line.charAt(0));
            if (!inBlock && line.equals(block + ":")) {
                inBlock = true;
                blockIndent = indent;
                continue;
            }
            if (!inBlock) {
                continue;
            }
            if (indent <= blockIndent) {
                break; // 出块
            }
            if (line.startsWith(wantedKey)) {
                return line.substring(wantedKey.length()).trim();
            }
        }
        throw new AssertionError("application.yml 的 nodes." + block + " 下找不到 " + key);
    }

    @Test
    void keyChainsIncludeBothLocalAndContainerVariableNames() throws IOException {
        String yml = yml();
        for (String block : List.of("llm", "image")) {
            String chain = valueInBlock(yml, block, "api-key");
            // 必须带冒号一起比：AI_API_KEY1 里就含有 "AI_API_KEY" 这段字，去掉冒号这条断言永远为真
            assertThat(chain)
                    .as("nodes.%s.api-key 必须同时能读本机按量 key(AI_API_KEY1) 与容器垫片落回的 AI_API_KEY", block)
                    .contains("${AI_API_KEY1:")
                    .contains("${AI_API_KEY:");
        }
    }

    @Test
    void defaultBaseUrlsMatchCodeConstantsWithoutPath() throws IOException {
        String yml = yml();
        String llmBase = defaultValue(valueInBlock(yml, "llm", "base-url"));
        String imageBase = defaultValue(valueInBlock(yml, "image", "base-url"));

        assertThat(llmBase).isEqualTo(SpringAiLlmGateway.DEFAULT_BASE_URL);
        assertThat(imageBase).isEqualTo(DashScopeImageClient.DEFAULT_BASE_URL);
        assertThat(llmBase).doesNotContain("/compatible-mode");
        assertThat(imageBase).doesNotContain("/compatible-mode");
    }

    /**
     * DB 口令不许有明文默认值（这条是被真实事故逼出来的：口令曾写进 yml 并随提交进了远端历史）。
     * 断言形式选"整行等于占位符且默认段为空"，而不是扫字符串长度——写死值的人通常连注释一起改，
     * 只有结构化的判据挡得住。
     */
    @Test
    void datasourcePasswordMustHaveNoBakedInDefault() throws IOException {
        for (String line : yml().split("\n")) {
            String t = line.trim();
            if (t.startsWith("password: ${PG_DB_PASSWORD")) {
                assertThat(t).as("DB 口令只能来自 env，不许写默认值").isEqualTo("password: ${PG_DB_PASSWORD:}");
                return;
            }
        }
        throw new AssertionError("application.yml 里没找到 spring.datasource.password 的占位符行");
    }

    /** `${NAME:default}` → default；`${NAME:}`（无默认，必须由 env 提供）→ 空串。 */
    private static String defaultValue(String placeholder) {
        String s = placeholder.trim();
        int firstColon = s.indexOf(':');
        int lastBrace = s.lastIndexOf('}');
        if (firstColon < 0 || lastBrace <= firstColon + 1) {
            return "";
        }
        return s.substring(firstColon + 1, lastBrace).trim();
    }

    /** 反向自检用的取样：确认解析器真的按块取值，而不是抓到了别处的同名键。 */
    @Test
    void blockParserIgnoresOtherSections() throws IOException {
        String sample = String.join("\n", new ArrayList<>(List.of(
                "agentflow:", "  nodes:", "    llm:", "      api-key: ${A:${AI_API_KEY1:${AI_API_KEY:}}}",
                "      base-url: https://x", "    image:", "      base-url: https://y")));
        assertThat(valueInBlock(sample, "image", "base-url")).isEqualTo("https://y");
    }
}

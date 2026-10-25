package com.agentflow.server.startup;

import com.agentflow.common.security.AesGcmCipher;
import com.agentflow.nodes.image.DashScopeImageClient;
import com.agentflow.nodes.image.ReferenceStyleAnalyzer;
import com.agentflow.nodes.llm.LlmGateway;
import com.agentflow.nodes.llm.SpringAiLlmGateway;
import com.agentflow.server.config.ContentProperties;
import com.agentflow.server.config.NodeConfigProperties;
import com.agentflow.server.security.SecurityProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动自检（skill §1/§2 DoD）：打印生效 profile、关键开关、密钥环境变量就位情况。
 * 只报"存在与否"，永不打印密钥值。
 */
@Component
public class StartupSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSelfCheck.class);

    private final Environment env;
    private final SecurityProperties securityProps;
    private final ContentProperties contentProps;
    private final NodeConfigProperties nodeProps;
    private final ObjectProvider<AesGcmCipher> cipherProvider;
    private final ObjectProvider<LlmGateway> gatewayProvider;

    public StartupSelfCheck(Environment env, SecurityProperties securityProps,
                            ContentProperties contentProps, NodeConfigProperties nodeProps,
                            ObjectProvider<AesGcmCipher> cipherProvider,
                            ObjectProvider<LlmGateway> gatewayProvider) {
        this.env = env;
        this.securityProps = securityProps;
        this.contentProps = contentProps;
        this.nodeProps = nodeProps;
        this.cipherProvider = cipherProvider;
        this.gatewayProvider = gatewayProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== agent-flow 启动自检 ===");
        log.info("active profiles : {}", Arrays.toString(env.getActiveProfiles()));
        log.info("server port     : {}", env.getProperty("server.port", "8080"));
        log.info("datasource      : {}", env.getProperty("spring.datasource.url", "(默认)"));
        log.info("security.enabled: {}", securityProps.enabled());
        log.info("AGENTFLOW_MASTER_KEY present : {} (kid={})",
                !securityProps.masterKey().isBlank(), masterKeyId());
        log.info("AGENTFLOW_MASTER_KEY_PREVIOUS present: {}", !securityProps.masterKeyPrevious().isBlank());
        if (!securityProps.masterKeyPrevious().isBlank()
                && securityProps.masterKeyPrevious().equals(securityProps.masterKey())) {
            log.warn("AGENTFLOW_MASTER_KEY_PREVIOUS 与主密钥相同，轮换未生效（双读无意义）");
        }
        log.info("AGENTFLOW_API_TOKEN present  : {}", !securityProps.apiToken().isBlank());
        log.info("AGENTFLOW_METRICS_TOKEN present: {}", !securityProps.metricsToken().isBlank());
        log.info("jwt-secret present           : {}", !securityProps.jwtSecret().isBlank());
        log.info("rate-limit per-minute        : {}", securityProps.rateLimitPerMinute());
        log.info("rate-limit auth per-ip       : {} (trust-forwarded-for={})",
                securityProps.rateLimitAuthPerMinute(), securityProps.trustForwardedFor());
        log.info("llm api-key present          : {}", !nodeProps.llm().apiKey().isBlank());
        log.info("image api-key present        : {}", !nodeProps.image().apiKey().isBlank());
        // 三个上游地址必须打出来：包月/按量两类 key 各自只在一个域名有效，
        // "配置里写的域名"和"实际发出去的 URL"不一致是这类问题唯一的可查证据（此处打后者）。
        log.info("llm endpoint                 : {}", llmEndpoint());
        String imageBase = imageBaseUrl();
        log.info("image endpoint               : {}{}", imageBase, DashScopeImageClient.SUBMIT_PATH);
        log.info("vision endpoint              : {}{}", imageBase, ReferenceStyleAnalyzer.MULTIMODAL_PATH);
        log.info("content library dir          : {}", checkLibrary());
    }

    /** 网关缺席（无 key）时打"网关未装配"，比打一个假 URL 诚实。 */
    private String llmEndpoint() {
        LlmGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return "(llm 网关未装配：agentflow.nodes.llm.api-key 为空)";
        }
        String endpoint = gateway.endpoint();
        return endpoint.isBlank() ? "(实现未提供 endpoint，默认 " + SpringAiLlmGateway.DEFAULT_BASE_URL + ")" : endpoint;
    }

    private String imageBaseUrl() {
        String base = nodeProps.image().baseUrl();
        return base == null || base.isBlank()
                ? DashScopeImageClient.DEFAULT_BASE_URL : trimTrailingSlash(base);
    }

    private static String trimTrailingSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * P3 硬基线：开鉴权必须配 JWT 密钥——缺密钥的"开鉴权"等于把所有请求关在门外或全部放行，两者都不可接受。
     * P7-2b：这条检查必须在**端口绑定之前**发生。放在 ApplicationRunner 里时，进程已经 listen 在 8080
     * 上、外部能连上又立刻失败（compose 的 healthcheck 与滚动发布都会看到这个"活着但必然拒服务"的窗口），
     * 而单例初始化早于 ServletWebServer 的 start()，所以 @PostConstruct 才是正确的时机。
     */
    @PostConstruct
    void enforceSecurityBaseline() {
        if (securityProps.enabled() && securityProps.jwtSecret().isBlank()) {
            throw new IllegalStateException("security.enabled=true 但未配置 AGENTFLOW_JWT_SECRET（fail-fast，禁止默认密钥上线）");
        }
        if ("prod".equals(String.join(",", env.getActiveProfiles())) && !securityProps.enabled()) {
            log.error("!! prod profile 下 security.enabled=false，违反安全基线，禁止对外服务 !!");
        }
    }

    /** 只回显主密钥指纹（SHA-256 前 8 字节），供轮换时核对生效密钥，不泄露密钥本身。 */
    private String masterKeyId() {
        AesGcmCipher cipher = cipherProvider.getIfAvailable();
        return cipher == null ? "n/a" : cipher.keyId();
    }

    /**
     * 成品库是发布闭环的最后一站，目录不可写等于"发出去的是空气"，所以启动时就试建一次并回显真实绝对路径。
     * 只记录状态，不抛异常：内容库缺席时 llm/chat 类工作流仍可运行，publish 节点会在执行时报配置错。
     */
    private String checkLibrary() {
        Path dir = Path.of(contentProps.libraryDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write-probe");
            Files.write(probe, new byte[] {1});
            Files.delete(probe);
            return dir + " (writable)";
        } catch (IOException e) {
            log.error("!! 成品库目录不可写：{} -> {}，publish.local / xhs.draft 执行将失败 !!", dir, e.getMessage());
            return dir + " (NOT writable: " + e.getClass().getSimpleName() + ")";
        }
    }
}

package com.agentflow.server.config;

import com.agentflow.nodes.http.HttpCollectNodeExecutor;
import com.agentflow.nodes.image.WanxiangImageNodeExecutor;
import com.agentflow.nodes.llm.LlmChatNodeExecutor;
import com.agentflow.nodes.llm.LlmGateway;
import com.agentflow.nodes.llm.SpringAiLlmGateway;
import com.agentflow.nodes.spi.CredentialProvider;
import com.agentflow.nodes.wechat.WechatDraftNodeExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.agentflow.server.config.NodeConfigProperties.blank;

/**
 * P2 节点装配。核心口径：**节点 Bean 无条件注册，凭证延迟到执行时刻判定**——
 * 本地无 key 也能保存/校验含 llm.chat 的 DSL（画布开发不被凭证阻塞），执行时才报 A0400 配置错误（不可重试）。
 *
 * LlmGateway Bean 返回 null → Spring 注册 NullBean → ObjectProvider.getIfAvailable() 视为缺席，
 * 这是"有 key 才建网关、节点始终在位"的标准装配缝。
 */
@Configuration
@EnableConfigurationProperties(NodeConfigProperties.class)
public class NodesConfig {

    @Bean
    public HttpCollectNodeExecutor httpCollectNodeExecutor() {
        return new HttpCollectNodeExecutor();
    }

    /** 无 BAILIAN_API_KEY 时返回 null（NullBean）：网关缺席，llm.chat 节点执行时报配置错误。 */
    @Bean
    public LlmGateway llmGateway(NodeConfigProperties props) {
        if (blank(props.llm().apiKey())) {
            return null;
        }
        String defaultModel = blank(props.llm().defaultModel()) ? "qwen-plus" : props.llm().defaultModel();
        return new SpringAiLlmGateway(props.llm().apiKey(), defaultModel);
    }

    @Bean
    public LlmChatNodeExecutor llmChatNodeExecutor(ObjectProvider<LlmGateway> gatewayProvider) {
        return new LlmChatNodeExecutor(gatewayProvider::getIfAvailable);
    }

    @Bean
    public WanxiangImageNodeExecutor wanxiangImageNodeExecutor(NodeConfigProperties props) {
        String baseUrl = blank(props.image().baseUrl()) ? "https://dashscope.aliyuncs.com" : props.image().baseUrl();
        return new WanxiangImageNodeExecutor(props.image().apiKey(), baseUrl, java.time.Duration.ofSeconds(30));
    }

    /** P3 凭证库接入：有 CredentialProvider（主密钥已配）则注入端口，wechat.draft 支持 per-execute credentialId。 */
    @Bean
    public WechatDraftNodeExecutor wechatDraftNodeExecutor(NodeConfigProperties props,
                                                           ObjectProvider<CredentialProvider> credentialProvider) {
        String baseUrl = blank(props.wechat().baseUrl()) ? "https://api.weixin.qq.com" : props.wechat().baseUrl();
        WechatDraftNodeExecutor executor = new WechatDraftNodeExecutor(
                props.wechat().appId(), props.wechat().appSecret(), baseUrl, java.time.Duration.ofSeconds(15));
        executor.setCredentialProvider(credentialProvider.getIfAvailable());
        return executor;
    }
}

package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.engine.registry.NodeRegistry;
import com.agentflow.nodes.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P2 装配验证：五个节点类型无条件在位（画布可存 DSL）；无 BAILIAN_API_KEY 环境下网关 Bean 缺席
 * 但应用照常启动——"凭证延迟判定"口径的上下文级钉桩。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-p2-wiring;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                // 抹掉宿主环境变量（用户台式机可能已全局设 BAILIAN_API_KEY），测试口径与真实环境无关
                "agentflow.nodes.llm.api-key=",
                "agentflow.nodes.image.api-key=",
                "agentflow.nodes.wechat.app-id=",
                "agentflow.nodes.wechat.app-secret="})
class P2NodeWiringTests {

    @Autowired
    NodeRegistry registry;

    @Autowired
    ObjectProvider<LlmGateway> gatewayProvider;

    @Test
    void allFiveNodeTypesRegistered() {
        assertThat(registry.knownTypes()).contains(
                "core.echo", "http.collect", "llm.chat", "image.wanxiang", "wechat.draft");
    }

    @Test
    void applicationStartsWithoutAnyApiKey() {
        // 测试环境未注入 BAILIAN_API_KEY：网关必须缺席（NullBean），但不影响上下文启动与节点注册
        assertThat(gatewayProvider.getIfAvailable()).isNull();
    }
}

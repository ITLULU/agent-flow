package com.agentflow.server.config;

import com.agentflow.engine.WorkflowEngine;
import com.agentflow.engine.port.ExecutionLogStore;
import com.agentflow.engine.registry.NodeRegistry;
import com.agentflow.nodes.core.EchoNodeExecutor;
import com.agentflow.server.resilience.DependencyGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agentflow.engine.spi.NodeExecutor;

/** 引擎装配：节点执行器以 Bean 形式自动注册（P2 新增节点只需声明 Bean，零改动这里）。 */
@Configuration
@EnableConfigurationProperties(ResilienceProperties.class)
public class EngineConfig {

    @Bean
    public EchoNodeExecutor echoNodeExecutor() {
        return new EchoNodeExecutor();
    }

    @Bean
    public NodeRegistry nodeRegistry(ObjectProvider<NodeExecutor> executors, DependencyGuard guard) {
        NodeRegistry registry = new NodeRegistry();
        // 包装点在注册处：执行器本身不感知舱壁/熔断，degrade/timeout 等元数据原样透传（P7-3#17）
        executors.forEach(executor -> registry.register(guard.wrap(executor)));
        return registry;
    }

    @Bean
    public WorkflowEngine workflowEngine(NodeRegistry registry, ExecutionLogStore store) {
        return new WorkflowEngine(registry, store);
    }
}

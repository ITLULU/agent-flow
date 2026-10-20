package com.agentflow.server.config;

import com.agentflow.engine.WorkflowEngine;
import com.agentflow.engine.port.ExecutionLogStore;
import com.agentflow.engine.registry.NodeRegistry;
import com.agentflow.nodes.core.EchoNodeExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agentflow.engine.spi.NodeExecutor;

/** 引擎装配：节点执行器以 Bean 形式自动注册（P2 新增节点只需声明 Bean，零改动这里）。 */
@Configuration
public class EngineConfig {

    @Bean
    public EchoNodeExecutor echoNodeExecutor() {
        return new EchoNodeExecutor();
    }

    @Bean
    public NodeRegistry nodeRegistry(ObjectProvider<NodeExecutor> executors) {
        NodeRegistry registry = new NodeRegistry();
        executors.forEach(registry::register);
        return registry;
    }

    @Bean
    public WorkflowEngine workflowEngine(NodeRegistry registry, ExecutionLogStore store) {
        return new WorkflowEngine(registry, store);
    }
}

package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DependencyIsolationTest {

    @Test
    void unusedNacosClientIsNotOnClasspath() {
        // spring-ai-alibaba-core 的传递依赖；本项目不接 Nacos，留着会把 /actuator/health 拉成 DOWN
        assertThatThrownBy(() ->
                Class.forName("com.alibaba.cloud.nacos.endpoint.NacosConfigHealthIndicator"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}

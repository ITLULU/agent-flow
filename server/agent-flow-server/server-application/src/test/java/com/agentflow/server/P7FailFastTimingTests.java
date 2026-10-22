package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * P7-2b：缺 jwt-secret 的 fail-fast 必须早于端口绑定。
 * 反向验证——启动失败后连这个端口，连上即说明"活着但必然拒服务"的窗口存在（评审 #5）。
 */
class P7FailFastTimingTests {

    @Test
    void enabledWithoutJwtSecretFailsBeforePortIsBound() throws IOException {
        int port = sparePort();
        ConfigurableApplicationContext ctx = null;
        Throwable failure = null;
        try {
            // 用命令行属性而非 builder.properties()：后者是 defaultProperties，优先级低于 application.yml，
            // 会被 yml 里的 server.port / security.enabled 覆盖掉，测出来的就不是我们要的场景
            ctx = new SpringApplicationBuilder(AgentFlowApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run("--server.port=" + port,
                            "--spring.datasource.url=jdbc:h2:mem:af-p7-failfast;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                            "--agentflow.security.enabled=true",
                            "--agentflow.security.jwt-secret=",
                            "--agentflow.security.rate-limit-per-minute=0");
        } catch (Throwable t) {
            failure = t;
        } finally {
            if (ctx != null) {
                ctx.close();
            }
        }

        assertThat(failure).as("enabled=true 且密钥为空必须启动失败").isNotNull();
        assertThat(chainOf(failure)).contains("AGENTFLOW_JWT_SECRET");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            fail("启动失败后端口 " + port + " 仍可连接 ⇒ fail-fast 晚于端口绑定");
        } catch (IOException expected) {
            // 期望：端口从未监听，连接被拒
        }
    }

    private static int sparePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String chainOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(':').append(c.getMessage()).append('\n');
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }
}

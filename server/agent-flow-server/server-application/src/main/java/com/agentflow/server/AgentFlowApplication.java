package com.agentflow.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.agentflow")
@EnableScheduling // P3 cron 触发扫描（ScheduleService.tick）；调度线程仅 1 个，扫描轻量 SQL 无阻塞风险
public class AgentFlowApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(AgentFlowApplication.class, args);
        } catch (RuntimeException | Error ex) {
            // 传递依赖（nacos-client）会留非守护线程：main 抛错后 JVM 仍活着，
            // 容器会停在 "Up 但端口永不绑定"，编排层只能靠 healthcheck 兜 ⇒ 这里显式退出。
            System.exit(1);
        }
    }
}

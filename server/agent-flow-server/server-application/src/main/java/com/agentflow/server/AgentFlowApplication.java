package com.agentflow.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.agentflow")
@EnableScheduling // P3 cron 触发扫描（ScheduleService.tick）；调度线程仅 1 个，扫描轻量 SQL 无阻塞风险
public class AgentFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentFlowApplication.class, args);
    }
}

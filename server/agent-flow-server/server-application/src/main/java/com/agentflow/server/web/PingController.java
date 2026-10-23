package com.agentflow.server.web;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.server.security.EndpointSensitivity;
import com.agentflow.server.security.Sensitivity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 底座连通性验证端点（健康检查走 actuator，这里验证 MVC + traceId 链路）。 */
@RestController
public class PingController {

    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping("/api/ping")
    public ApiResponse<String> ping() {
        return ApiResponse.ok("pong");
    }
}

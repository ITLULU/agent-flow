package com.agentflow.server.web;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.server.security.AuthService;
import com.agentflow.server.security.EndpointSensitivity;
import com.agentflow.server.security.Sensitivity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 注册/登录入口（AuthFilter 白名单，无需 token 可达）。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final ObjectMapper mapper;

    public AuthController(AuthService authService, ObjectMapper mapper) {
        this.authService = authService;
        this.mapper = mapper;
    }

    @EndpointSensitivity(Sensitivity.PUBLIC)
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody String rawJson) {
        JsonNode node = parse(rawJson);
        AuthService.Registration r = authService.register(node.path("username").asText(null),
                node.path("password").asText(null));
        return ApiResponse.ok(Map.of("uid", r.uid(), "username", r.username(), "role", r.role()));
    }

    @EndpointSensitivity(Sensitivity.PUBLIC)
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody String rawJson) {
        JsonNode node = parse(rawJson);
        return ApiResponse.ok(authService.login(node.path("username").asText(null),
                node.path("password").asText(null)));
    }

    private JsonNode parse(String rawJson) {
        try {
            return mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "JSON 解析失败: " + e.getClass().getSimpleName());
        }
    }
}

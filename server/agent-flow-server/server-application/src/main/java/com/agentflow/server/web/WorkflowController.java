package com.agentflow.server.web;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.trace.TraceIdUtils;
import com.agentflow.engine.dag.DagValidator;
import com.agentflow.engine.model.WorkflowDefinition;
import com.agentflow.engine.registry.NodeRegistry;
import com.agentflow.server.exec.ExecutionManager;
import com.agentflow.server.persistence.ExecutionQueryRepository;
import com.agentflow.server.persistence.ExecutionQueryRepository.ExecutionView;
import com.agentflow.server.persistence.WorkflowCatalog;
import com.agentflow.server.persistence.WorkflowCatalog.StoredWorkflow;
import com.agentflow.server.schedule.ScheduleService;
import com.agentflow.server.security.PrincipalContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作流平台 API。保存即校验：非法 DSL 永远进不了库，引擎执行时二次校验兜底（防旁路写入）。
 * P3 归属隔离：非属主（且非 admin）访问他人工作流/执行一律 404——不区分 403/404，防资源存在性探测。
 */
@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final WorkflowCatalog catalog;
    private final NodeRegistry registry;
    private final ExecutionManager manager;
    private final ExecutionQueryRepository executionQuery;
    private final ScheduleService scheduleService;
    private final ObjectMapper mapper;

    public WorkflowController(WorkflowCatalog catalog, NodeRegistry registry,
                              ExecutionManager manager, ExecutionQueryRepository executionQuery,
                              ScheduleService scheduleService, ObjectMapper mapper) {
        this.catalog = catalog;
        this.registry = registry;
        this.manager = manager;
        this.executionQuery = executionQuery;
        this.scheduleService = scheduleService;
        this.mapper = mapper;
    }

    @PostMapping("/workflows")
    public ApiResponse<Map<String, Object>> create(@RequestBody String rawJson) {
        WorkflowDefinition def = parse(rawJson);
        DagValidator.ValidationResult vr = DagValidator.validate(def, registry.knownTypes());
        if (!vr.valid()) {
            throw new ApiException(ErrorCode.WORKFLOW_INVALID, String.join("; ", vr.errors()));
        }
        long id = catalog.save(def, PrincipalContext.get().uid());
        return ApiResponse.ok(Map.of("workflowId", id));
    }

    @GetMapping("/workflows/{id}")
    public ApiResponse<WorkflowDefinition> get(@PathVariable long id) {
        return ApiResponse.ok(catalog.parseDsl(requireVisible(id)));
    }

    @PostMapping("/workflows/{id}/executions")
    public ApiResponse<Map<String, Object>> trigger(@PathVariable long id) {
        WorkflowDefinition def = catalog.parseDsl(requireVisible(id));
        String traceId = TraceIdUtils.current();
        long executionId = manager.submit(id, def, traceId);
        return ApiResponse.ok(Map.of("executionId", executionId, "traceId", traceId == null ? "" : traceId));
    }

    @GetMapping("/executions/{id}")
    public ApiResponse<ExecutionView> execution(@PathVariable long id) {
        ExecutionView view = executionQuery.findById(id);
        requireVisible(view.workflowId()); // 执行随属主工作流授权，防跨用户读日志
        return ApiResponse.ok(view);
    }

    /** 登记/更新 cron 调度：{cron:"0 0/5 * * * *", enabled:true}；cron 非法 A0400。 */
    @PutMapping("/workflows/{id}/schedule")
    public ApiResponse<Map<String, Object>> schedule(@PathVariable long id, @RequestBody String rawJson) {
        requireVisible(id);
        JsonNode node = parseNode(rawJson);
        String next = scheduleService.setSchedule(id,
                node.path("cron").asText(null), node.path("enabled").asBoolean(false));
        return ApiResponse.ok(Map.of("workflowId", id, "nextFireAt", next == null ? "" : next));
    }

    @DeleteMapping("/workflows/{id}/schedule")
    public ApiResponse<Map<String, Object>> unschedule(@PathVariable long id) {
        requireVisible(id);
        scheduleService.setSchedule(id, null, false);
        return ApiResponse.ok(Map.of("workflowId", id, "scheduleEnabled", false));
    }

    private StoredWorkflow requireVisible(long id) {
        StoredWorkflow stored = catalog.findById(id);
        if (!PrincipalContext.get().canAccess(stored.ownerId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "workflow " + id); // 统一 404：不泄露"存在但无权"
        }
        return stored;
    }

    private WorkflowDefinition parse(String rawJson) {
        try {
            return mapper.readValue(rawJson, WorkflowDefinition.class);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "DSL JSON 解析失败: " + e.getClass().getSimpleName());
        }
    }

    private JsonNode parseNode(String rawJson) {
        try {
            return mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "JSON 解析失败: " + e.getClass().getSimpleName());
        }
    }
}

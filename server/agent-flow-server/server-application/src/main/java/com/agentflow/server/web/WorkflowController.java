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
import com.agentflow.server.security.EndpointSensitivity;
import com.agentflow.server.security.PrincipalContext;
import com.agentflow.server.security.Sensitivity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作流平台 API。保存即校验：非法 DSL 永远进不了库，引擎执行时二次校验兜底（防旁路写入）。
 * P3 归属隔离：非属主（且非 admin）访问他人工作流/执行一律 404——不区分 403/404，防资源存在性探测。
 */
@RestController
@RequestMapping("/api")
public class WorkflowController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

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

    @EndpointSensitivity(Sensitivity.USER)
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

    /** 自己的清单；admin 看全部。只回摘要，画布按需再拉单条完整 DSL。beforeId 为游标（不含）。 */
    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping("/workflows")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int limit,
            @RequestParam(required = false) Long beforeId) {
        var principal = PrincipalContext.get();
        int size = clampLimit(limit);
        var items = catalog.list(principal.isAdmin() ? null : principal.uid(), size, beforeId);
        Long nextCursor = items.size() == size ? items.get(items.size() - 1).id() : null;
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("nextCursor", nextCursor);
        return ApiResponse.ok(page);
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
    }

    /** 覆盖保存（画布"保存"按钮）：归属校验 + 保存即校验，版本号由仓库递增。 */
    @EndpointSensitivity(Sensitivity.USER)
    @PutMapping("/workflows/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody String rawJson) {
        requireVisible(id);
        WorkflowDefinition def = parse(rawJson);
        DagValidator.ValidationResult vr = DagValidator.validate(def, registry.knownTypes());
        if (!vr.valid()) {
            throw new ApiException(ErrorCode.WORKFLOW_INVALID, String.join("; ", vr.errors()));
        }
        catalog.update(id, def);
        return ApiResponse.ok(Map.of("workflowId", id));
    }

    /**
     * 删除 = 逻辑删除（画布上消失，库里行、DSL、执行记录、成品目录都留着）。
     * 归属校验与读改同一条路径：非属主且非 admin ⇒ 404，不区分"不存在"与"无权"。
     * 删除动作本身顺带关掉调度（见 {@link com.agentflow.server.persistence.WorkflowCatalog#softDelete}），
     * 所以不需要调用方再补一次 DELETE /schedule——漏那一步就是"删掉的流水线每天定时扣钱"。
     */
    @EndpointSensitivity(Sensitivity.USER)
    @DeleteMapping("/workflows/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id) {
        requireVisible(id);
        catalog.softDelete(id);
        return ApiResponse.ok(Map.of("workflowId", id, "deleted", true));
    }

    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping("/workflows/{id}")
    public ApiResponse<WorkflowDefinition> get(@PathVariable long id) {
        return ApiResponse.ok(catalog.parseDsl(requireVisible(id)));
    }

    @EndpointSensitivity(Sensitivity.USER)
    @PostMapping("/workflows/{id}/executions")
    public ApiResponse<Map<String, Object>> trigger(@PathVariable long id) {
        StoredWorkflow stored = requireVisible(id);
        WorkflowDefinition def = catalog.parseDsl(stored);
        String traceId = TraceIdUtils.current();
        // 归属人随提交一起传下去：跨实例配额的单用户上限以 workflow.owner_id 为准，
        // 不看调用方会话——cron 触发的执行没有"当前登录者"。
        long executionId = manager.submit(id, stored.ownerId(), def, traceId);
        return ApiResponse.ok(Map.of("executionId", executionId, "traceId", traceId == null ? "" : traceId));
    }

    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping("/executions/{id}")
    public ApiResponse<ExecutionView> execution(@PathVariable long id) {
        ExecutionView view = executionQuery.findById(id);
        requireVisible(view.workflowId()); // 执行随属主工作流授权，防跨用户读日志
        return ApiResponse.ok(view);
    }

    /** 登记/更新 cron 调度：{cron:"0 0/5 * * * *", enabled:true}；cron 非法 A0400。 */
    @EndpointSensitivity(Sensitivity.USER)
    @PutMapping("/workflows/{id}/schedule")
    public ApiResponse<Map<String, Object>> schedule(@PathVariable long id, @RequestBody String rawJson) {
        requireVisible(id);
        JsonNode node = parseNode(rawJson);
        String cron = node.path("cron").asText(null);
        // 省略 enabled 时默认为"开"：PUT 带 cron 的语义是登记调度，若默认 false 会静默变成撤销调度，
        // 调用方拿到 200 却以为定时已生效。显式关闭必须写 enabled:false（或走 DELETE）。
        boolean hasCron = cron != null && !cron.isBlank();
        boolean enabled = node.has("enabled") ? node.path("enabled").asBoolean(false) : hasCron;
        if (enabled && !hasCron) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "enabled=true 时 cron 不能为空");
        }
        String next = scheduleService.setSchedule(id, hasCron ? cron : null, enabled);
        return ApiResponse.ok(Map.of("workflowId", id, "nextFireAt", next == null ? "" : next));
    }

    @EndpointSensitivity(Sensitivity.USER)
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

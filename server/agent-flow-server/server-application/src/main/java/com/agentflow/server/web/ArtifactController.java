package com.agentflow.server.web;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.server.persistence.JdbcContentStore;
import com.agentflow.server.security.EndpointSensitivity;
import com.agentflow.server.security.PrincipalContext;
import com.agentflow.server.security.Sensitivity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成品库 API：本地存储是发布闭环的第一步（用户口径：先本地，再公众号草稿），所以产物必须能被读取与下载。
 *
 * 归属隔离沿用工作流口径：非属主（且非 admin）访问他人成品一律 404，不区分 403/404，防 id 枚举探测。
 * 文件下载只允许成品目录内的相对路径——穿越校验收敛在 {@link JdbcContentStore#resolveFile}，
 * 并额外要求路径出现在 filesOf() 白名单里（双保险：即便 normalize 漏判也不会读到别人的目录）。
 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private static final int DEFAULT_LIMIT = 20;

    private final JdbcContentStore store;

    public ArtifactController(JdbcContentStore store) {
        this.store = store;
    }

    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) Long beforeId) {
        int size = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> items = store.list(PrincipalContext.get().uid(), kind, size, beforeId);
        Long nextCursor = items.size() == size
                ? ((Number) items.get(items.size() - 1).get("id")).longValue() : null;
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("nextCursor", nextCursor);
        return ApiResponse.ok(page);
    }

    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(requireVisible(id));
    }

    /** 成品目录内的文件清单（相对路径），供前端与下载接口对齐。 */
    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping("/{id}/files")
    public ApiResponse<List<String>> files(@PathVariable long id) {
        requireVisible(id);
        return ApiResponse.ok(store.filesOf(id));
    }

    /**
     * 完整性核对：列出"库里有行、盘上未必有货"的成品（file_state ≠ complete）。
     * 这是评审 #12 要的可见性——半套数据过去只能靠人工 diff 目录发现。
     */
    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping("/incomplete")
    public ApiResponse<Map<String, Object>> incomplete(
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) Long beforeId) {
        int size = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> items = store.incomplete(PrincipalContext.get().uid(), size, beforeId);
        Long nextCursor = items.size() == size
                ? ((Number) items.get(items.size() - 1).get("id")).longValue() : null;
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("nextCursor", nextCursor);
        return ApiResponse.ok(page);
    }

    /** 补偿入口：用库里的正文/元数据重建成品文本；图片不在库里，缺就照实回 missing 并保持 incomplete。 */
    @EndpointSensitivity(Sensitivity.USER)
    @PostMapping("/{id}/repair")
    public ApiResponse<Map<String, Object>> repair(@PathVariable long id) {
        requireVisible(id);
        return ApiResponse.ok(store.repair(id));
    }

    /** 下载单个产物文件（article.md / note.txt / assets/cover-1-3x4.png …）。 */
    @EndpointSensitivity(Sensitivity.USER)
    @GetMapping("/{id}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable long id, @RequestParam String path) {
        requireVisible(id);
        List<String> allowed = store.filesOf(id);
        if (!allowed.contains(path)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "成品文件不存在: " + path);
        }
        Path target = store.resolveFile(id, path);
        if (!Files.isRegularFile(target)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "成品文件不存在: " + path);
        }
        return ResponseEntity.ok()
                .contentType(mediaType(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(target.getFileName().toString())
                        .build().toString())
                .contentLength(sizeOf(target))
                .body(new FileSystemResource(target));
    }

    private Map<String, Object> requireVisible(long id) {
        Map<String, Object> row = store.find(id);
        if (row.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "artifact " + id);
        }
        long ownerId = ((Number) row.get("owner_id")).longValue();
        if (!PrincipalContext.get().canAccess(ownerId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "artifact " + id); // 统一 404：不泄露"存在但无权"
        }
        return row;
    }

    private static long sizeOf(Path target) {
        try {
            return Files.size(target);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL, "产物大小读取失败: " + e.getClass().getSimpleName());
        }
    }

    /** 按扩展名给类型；未知一律 application/octet-stream，绝不猜测成 text/html（防内容嗅探）。 */
    private static MediaType mediaType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (p.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (p.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (p.endsWith(".md") || p.endsWith(".txt")) {
            return MediaType.parseMediaType("text/plain;charset=UTF-8");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}

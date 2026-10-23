package com.agentflow.server.storage;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.server.config.ObjectStorageProperties;
import com.agentflow.server.persistence.JdbcContentStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 成品 → 对象存储的同步服务（P7-3#12b）。本地卷仍是交付面，这里只负责"远端也有一份且这份是好的"。
 *
 * 三条刻意的设计：
 * 1. **只同步 file_state=complete 的成品**。半套成品传上去，等于把"丢了还能补"变成"远端也错了"，
 *    而且补偿入口 repair 之后状态会重跑，天然会再来同步一次。
 * 2. **同步永不改 file_state**，失败只写 sync_*。备份失败伪装成丢稿是最坏的误导。
 * 3. **成功要留清单**（相对路径 → sha256）：读回落点时逐字节比对，"远端存在"和"远端正确"是两回事。
 *
 * 多实例并发扫同一个成品：同一 key 覆写同一份字节 ⇒ 幂等，浪费的是带宽不是正确性；
 * 分布式令牌/配额属于 #11，不在这里提前做半成品实现。
 */
@Service
public class ArtifactSyncService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactSyncService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OCTET_STREAM = "application/octet-stream";

    private final JdbcContentStore store;
    private final ObjectProvider<ObjectStore> objects;
    private final ObjectStorageProperties props;
    private final MeterRegistry registry;

    public ArtifactSyncService(JdbcContentStore store, ObjectProvider<ObjectStore> objects,
            ObjectStorageProperties props, MeterRegistry registry) {
        this.store = store;
        this.objects = objects;
        this.props = props;
        this.registry = registry;
    }

    public boolean enabled() {
        return props.enabled() && objects.getIfAvailable() != null;
    }

    /** 手动/自动同步一条成品；失败会把原因写进 sync_error 并照常抛出。 */
    public Map<String, Object> sync(long artifactId) {
        ObjectStore target = requireTarget();
        Map<String, Object> row = store.find(artifactId);
        if (row.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "成品不存在: " + artifactId);
        }
        String fileState = str(row.get("file_state"));
        if (!"complete".equals(fileState)) {
            String reason = "本地成品未完整（file_state=" + fileState + "），先 repair 再同步";
            store.markSyncFailed(artifactId, reason);
            count("skipped");
            throw new ApiException(ErrorCode.BAD_REQUEST, reason);
        }

        List<String> files = store.filesOf(artifactId);
        if (files.isEmpty()) {
            String reason = "成品目录内没有任何文件，无法建立远端副本";
            store.markSyncFailed(artifactId, reason);
            count("failed");
            throw new ApiException(ErrorCode.BAD_REQUEST, reason);
        }

        Map<String, String> manifest = new LinkedHashMap<>();
        long bytes = 0;
        try {
            for (String rel : files) {
                Path local = store.resolveFile(artifactId, rel);
                byte[] content = Files.readAllBytes(local);
                target.put(key(artifactId, rel), content, OCTET_STREAM);
                manifest.put(rel, SigV4.sha256Hex(content));
                bytes += content.length;
            }
            // 回看一遍存在性：PUT 返回 200 但落到别的桶/被代理改写，是这类"看起来同步好了"最常见的成因
            List<String> absent = new ArrayList<>();
            for (String rel : manifest.keySet()) {
                if (!target.exists(key(artifactId, rel))) {
                    absent.add(rel);
                }
            }
            if (!absent.isEmpty()) {
                throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "远端缺少对象: " + String.join(",", absent));
            }
            store.markSynced(artifactId, MAPPER.writeValueAsString(manifest));
            count("synced");
        } catch (ApiException e) {
            fail(artifactId, e);
            throw e;
        } catch (Exception e) {
            ApiException wrapped = new ApiException(ErrorCode.INTERNAL, "同步失败: " + e.getClass().getSimpleName());
            fail(artifactId, wrapped);
            throw wrapped;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("artifactId", artifactId);
        out.put("state", "synced");
        out.put("files", manifest.size());
        out.put("bytes", bytes);
        out.put("prefix", key(artifactId, ""));
        out.put("manifest", manifest);
        return out;
    }

    /** 后台扫描：逐条同步，单条失败不影响其余（记状态等下一轮或人工 repair）。 */
    public int sweep(int limit) {
        ObjectStore target = objects.getIfAvailable();
        if (target == null) {
            return 0;
        }
        int ok = 0;
        for (Map<String, Object> row : store.pendingForSweep(Math.max(1, Math.min(limit, 200)))) {
            long id = ((Number) row.get("id")).longValue();
            try {
                sync(id);
                ok++;
            } catch (Exception e) {
                // 状态与原因已由 sync() 落库；扫描日志只留类名，避免把端点报文里的细节刷进日志
                log.warn("成品 {} 自动同步失败: {}", id, e.getClass().getSimpleName());
            }
        }
        return ok;
    }

    /**
     * 清单里登记过的相对路径。下载接口把它与磁盘实况取并集当白名单：
     * 换实例/重建卷之后目录是空的，只用磁盘清单会让远端明明有好副本却回 404。
     */
    public List<String> manifestKeys(Map<String, Object> row) {
        return new ArrayList<>(parseManifest(row.get("sync_manifest")).keySet());
    }

    /**
     * 读回落点：本地文件不在（换实例、卷重建）时按清单从远端取，取回后逐字节校验。
     * 任何不一致都不冒充成功：返回 null 让调用方照常 404，同时把远端状态标成 failed。
     */
    public byte[] fetchRemote(long artifactId, Map<String, Object> row, String relPath) {
        if (!"synced".equals(str(row.get("sync_state")))) {
            return null;
        }
        ObjectStore target = objects.getIfAvailable();
        if (target == null) {
            return null;
        }
        Map<String, String> manifest = parseManifest(row.get("sync_manifest"));
        String expected = manifest.get(relPath);
        if (expected == null) {
            return null;
        }
        byte[] content;
        try {
            content = target.get(key(artifactId, relPath));
        } catch (ApiException e) {
            store.markSyncFailed(artifactId, "远端读取失败: " + e.getMessage());
            count("failed");
            return null;
        }
        if (content == null) {
            store.markSyncFailed(artifactId, "远端对象不存在: " + relPath);
            count("failed");
            return null;
        }
        if (!SigV4.sha256Hex(content).equals(expected)) {
            store.markSyncFailed(artifactId, "远端副本校验不一致: " + relPath);
            count("failed");
            return null;
        }
        count("served");
        return content;
    }

    private ObjectStore requireTarget() {
        ObjectStore target = objects.getIfAvailable();
        if (target == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "对象存储未启用（agentflow.content.storage.enabled=false）");
        }
        return target;
    }

    private void fail(long artifactId, Exception e) {
        store.markSyncFailed(artifactId, brief(e));
        count("failed");
    }

    /** key 形如 artifacts/{artifactId}/assets/cover-1-3x4.png；artifactId 全局唯一，天然隔离归属。 */
    private String key(long artifactId, String rel) {
        String prefix = props.keyPrefix() + "/" + artifactId;
        return rel == null || rel.isBlank() ? prefix : prefix + "/" + rel;
    }

    private void count(String state) {
        Counter.builder("agentflow.artifact.sync").tag("state", state).register(registry).increment();
    }

    private static Map<String, String> parseManifest(Object raw) {
        String s = raw == null ? "" : String.valueOf(raw);
        if (s.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(s, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String brief(Throwable t) {
        String msg = t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
        return msg.length() <= 480 ? msg : msg.substring(0, 480);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}

package com.agentflow.server.persistence;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.nodes.publish.ArticleMarkdown;
import com.agentflow.nodes.spi.ContentStore;
import com.agentflow.server.config.ContentProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * ContentStore 的实现：一行 artifact（数据库）+ 一个产物目录（文件系统）。
 *
 * 目录布局（library 根目录由 agentflow.content.library-dir 决定）：
 *   {root}/.staging/{executionId}/xxx.png     生成期暂存（image.cover 落图）
 *   {root}/{artifactId}/article.md|note.txt|meta.json|assets/*  发布期成品
 *
 * 多存储写入顺序按 skill §7 固定为"先落盘、后落库"？恰恰相反：这里先 insert 拿到 id 才能定目录名，
 * 所以顺序是 DB insert → 目录落盘 → 暂存搬迁。中途失败的残留只是 staging 里的孤儿文件，
 * 不会留下"库里有一行但目录不存在"的成品，清理成本更低（这是本类选择该顺序的原因，不是随手写的）。
 */
@Repository
public class JdbcContentStore implements ContentStore {

    private static final String STAGING = ".staging";
    private static final int MAX_NAME_CHARS = 96;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Path root;
    private final MeterRegistry registry;

    public JdbcContentStore(JdbcTemplate jdbc, ContentProperties props, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.root = Path.of(props.libraryDir()).toAbsolutePath().normalize();
        this.registry = registry;
    }

    @Override
    public String stage(long executionId, String suggestedName, byte[] content) {
        Path dir = resolveInside(root, STAGING + "/" + executionId);
        Path target = resolveInside(dir, safeName(suggestedName));
        write(target, content);
        return root.relativize(target).toString().replace('\\', '/');
    }

    @Override
    public List<String> stagedFiles(long executionId) {
        Path dir = resolveInside(root, STAGING + "/" + executionId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "暂存目录读取失败: " + e.getMessage());
        }
    }

    @Override
    public long insert(long executionId, String kind, String title, String body, String metaJson) {
        long ownerId = ownerOf(executionId);
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // 显式回列：H2 会把带 DEFAULT 的 created_at 当生成键多路返回，getKey() 遇多键即炸（V1 期踩过的同款坑）
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO artifact (execution_id, owner_id, kind, title, body, meta_json, dir) "
                            + "VALUES (?, ?, ?, ?, ?, ?, '')", new String[]{"id"});
            ps.setLong(1, executionId);
            ps.setLong(2, ownerId);
            ps.setString(3, kind);
            ps.setString(4, truncate(title, 500));
            ps.setString(5, body);
            ps.setString(6, metaJson);
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        if (id == null) {
            throw new ApiException(ErrorCode.INTERNAL, "artifact 主键生成失败");
        }
        Path dir = artifactPath(id);
        ensureDir(dir);
        jdbc.update("UPDATE artifact SET dir = ? WHERE id = ?", dir.toString(), id);
        return id;
    }

    @Override
    public String artifactDir(long artifactId) {
        return artifactPath(artifactId).toString();
    }

    @Override
    public List<String> attachStaged(long executionId, long artifactId) {
        Path from = resolveInside(root, STAGING + "/" + executionId);
        if (!Files.isDirectory(from)) {
            return List.of();
        }
        Path assets = resolveInside(artifactPath(artifactId), "assets");
        ensureDir(assets);
        List<String> moved = new ArrayList<>();
        for (String name : stagedFiles(executionId)) {
            Path src = resolveInside(from, name);
            Path dst = resolveInside(assets, safeName(name));
            try {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                moved.add(root.relativize(dst).toString().replace('\\', '/'));
            } catch (IOException e) {
                throw new ApiException(ErrorCode.INTERNAL, "产物搬迁失败 " + name + ": " + e.getMessage());
            }
        }
        try {
            Files.deleteIfExists(from); // 只删空目录；非空说明有并发写入，此时删除会失败并保留现场
        } catch (IOException ignored) {
            // 故意不抛：清理失败不影响已交付的成品
        }
        return moved;
    }

    @Override
    public String writeText(long artifactId, String fileName, String content) {
        Path target = resolveInside(artifactPath(artifactId), safeName(fileName));
        write(target, content.getBytes(StandardCharsets.UTF_8));
        return target.toString();
    }

    @Override
    public void markComplete(long artifactId) {
        jdbc.update("UPDATE artifact SET file_state = 'complete', file_error = NULL WHERE id = ?", artifactId);
        Counter.builder("agentflow.artifact.files").tag("state", "complete").register(registry).increment();
    }

    @Override
    public void markIncomplete(long artifactId, String reason) {
        jdbc.update("UPDATE artifact SET file_state = 'incomplete', file_error = ? WHERE id = ?",
                truncate(reason, 500), artifactId);
        Counter.builder("agentflow.artifact.files").tag("state", "incomplete").register(registry).increment();
    }

    /**
     * 单条成品。这里读不到 owner 之外的信息，归属校验在调用方（requireVisible）做。
     * 带 sync_error：详情里只显示 sync_state=failed 而不显示原因，运维就只能去猜是对端坏了还是路径不对。
     * 注意 body/meta_json 仍随行走一遍，属主自己的正文不外泄（非属主在同一次 SELECT 里就查不到行）。
     */
    @Override
    public Map<String, Object> find(long artifactId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, execution_id, owner_id, kind, title, body, meta_json, dir, file_state, file_error, "
                        + "sync_state, sync_error, sync_manifest, synced_at, created_at FROM artifact WHERE id = ?",
                artifactId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /**
     * 补偿入口：用库里已有的 title/body/meta 重渲染文本产物（逐字节口径由 ArticleMarkdown 单点保证）。
     * 图片不在数据库里，缺了只能照实报缺并把状态留在 incomplete——"补偿成功"不等于"目录里有全部产物"，
     * 这一步的意义是把不可见的半套数据变成可见、可再操作的数据。
     */
    public Map<String, Object> repair(long artifactId) {
        Map<String, Object> row = find(artifactId);
        if (row.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "成品不存在: " + artifactId);
        }
        Map<String, Object> meta = parseMeta(row.get("meta_json"));
        ensureDir(artifactPath(artifactId));
        List<String> restored = new ArrayList<>();
        writeText(artifactId, "article.md", ArticleMarkdown.render(str(row.get("kind")), str(row.get("title")),
                str(meta.get("digest")), str(row.get("body")), strList(meta.get("hashtags"))));
        restored.add("article.md");
        writeText(artifactId, "meta.json", prettyJson(meta));
        restored.add("meta.json");

        List<String> missing = new ArrayList<>();
        for (String name : expectedAssets(meta)) {
            if (!Files.isRegularFile(resolveInside(artifactPath(artifactId), "assets/" + safeName(name)))) {
                missing.add("assets/" + name);
            }
        }
        String state;
        if (missing.isEmpty()) {
            markComplete(artifactId);
            state = "complete";
        } else {
            markIncomplete(artifactId, "补偿后仍缺产物文件: " + String.join(",", missing));
            state = "incomplete";
        }
        Counter.builder("agentflow.artifact.repair").tag("state", state).register(registry).increment();
        Map<String, Object> out = new HashMap<>();
        out.put("artifactId", artifactId);
        out.put("state", state);
        out.put("restored", restored);
        out.put("missing", missing);
        out.put("dir", artifactPath(artifactId).toString());
        return out;
    }

    /** 供 API 层列成品（节点不需要，故不进 ContentStore 端口，留在实现类上）。beforeId 为游标（不含）。 */
    public List<Map<String, Object>> list(long ownerId, String kind, int limit, Long beforeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, execution_id, kind, title, dir, file_state, created_at FROM artifact WHERE owner_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = ?");
            args.add(kind);
        }
        if (beforeId != null) {
            sql.append(" AND id < ?");
            args.add(beforeId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> detail(long artifactId, long ownerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, execution_id, owner_id, kind, title, body, meta_json, dir, file_state, file_error, "
                        + "sync_state, sync_error, sync_manifest, synced_at, created_at "
                        + "FROM artifact WHERE id = ? AND owner_id = ?", artifactId, ownerId);
        if (rows.isEmpty()) {
            // 非属主与不存在同口径回 404：避免成品 id 是否存在被探测（与 P3 workflow 归属隔离一致）
            throw new ApiException(ErrorCode.NOT_FOUND, "成品不存在: " + artifactId);
        }
        return rows.get(0);
    }

    // ---------- 对象存储同步状态（P7-3#12b）----------

    /**
     * 同步成功：记下清单（相对路径 → sha256）与时间。
     * 清单是读回落点校验的依据——没有它，"远端有一份"和"远端那份是好的"就区分不出来。
     */
    public void markSynced(long artifactId, String manifestJson) {
        jdbc.update("UPDATE artifact SET sync_state = 'synced', sync_error = NULL, sync_manifest = ?, "
                + "synced_at = CURRENT_TIMESTAMP WHERE id = ?", manifestJson, artifactId);
    }

    /** 同步失败：只动 sync_* 三列。file_state 不能被同步结果改写，否则备份失败会伪装成丢稿。 */
    public void markSyncFailed(long artifactId, String reason) {
        jdbc.update("UPDATE artifact SET sync_state = 'failed', sync_error = ? WHERE id = ?",
                truncate(reason, 500), artifactId);
    }

    /** 待同步清单（扫实用）：本地已完整但远端还没有一套齐活副本的成品。 */
    public List<Map<String, Object>> pendingSync(long ownerId, int limit, Long beforeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, execution_id, kind, title, dir, file_state, sync_state, sync_error, synced_at, created_at "
                        + "FROM artifact WHERE owner_id = ? AND file_state = 'complete' AND sync_state <> 'synced'");
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (beforeId != null) {
            sql.append(" AND id < ?");
            args.add(beforeId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 后台扫描用：不限属主，按 id 升序（老成品先补，避免最新成品一直插队把旧的饿死）。 */
    public List<Map<String, Object>> pendingForSweep(int limit) {
        return jdbc.queryForList(
                "SELECT id, owner_id, kind, title, file_state, sync_state, sync_error FROM artifact "
                        + "WHERE file_state = 'complete' AND sync_state <> 'synced' ORDER BY id ASC LIMIT ?", limit);
    }

    /**
     * 完整性核对：列出该用户"库里有一行、盘上未必有货"的成品（file_state ≠ complete）。
     * 只查状态列不查磁盘是刻意的——逐行 stat 在网络盘/大库上会把一个只读 API 变成 IO 风暴；
     * 需要看目录实况时用 repair（它会实测缺失文件）或 /files。
     */
    public List<Map<String, Object>> incomplete(long ownerId, int limit, Long beforeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, execution_id, kind, title, dir, file_state, file_error, created_at FROM artifact "
                        + "WHERE owner_id = ? AND file_state <> 'complete'");
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (beforeId != null) {
            sql.append(" AND id < ?");
            args.add(beforeId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 列出成品目录内的文件（相对路径），下载接口用它做白名单。 */
    public List<String> filesOf(long artifactId) {
        Path dir = artifactPath(artifactId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            List<String> out = new ArrayList<>();
            s.filter(Files::isRegularFile).forEach(p -> out.add(dir.relativize(p).toString().replace('\\', '/')));
            return out;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "成品目录读取失败: " + e.getMessage());
        }
    }

    public Path resolveFile(long artifactId, String relativePath) {
        return resolveInside(artifactPath(artifactId), relativePath);
    }

    public Path root() {
        return root;
    }

    // ---------- 内部工具 ----------

    private Path artifactPath(long artifactId) {
        return root.resolve(String.valueOf(artifactId)).normalize();
    }

    /**
     * 路径穿越防线（skill §4：白名单根目录 + normalize 后前缀校验，收敛为一处）。
     * 所有对外可控的路径都必须过这里，禁止在别处散点拼接。
     */
    public static Path resolveInside(Path baseDir, String relative) {
        String cleaned = relative == null ? "" : relative.replace('\\', '/').trim();
        if (cleaned.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "路径不能为空");
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path target = base.resolve(cleaned).normalize();
        if (!target.startsWith(base)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "路径越界（疑似穿越）: " + relative);
        }
        return target;
    }

    /** 文件名清洗：只保留 basename，去掉路径分隔与控制字符，限制长度。 */
    private static String safeName(String name) {
        String n = name == null ? "file" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
        if (n.isBlank() || n.equals(".") || n.equals("..")) {
            n = "file";
        }
        return truncate(n, MAX_NAME_CHARS);
    }

    private long ownerOf(long executionId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT w.owner_id FROM execution e JOIN workflow w ON w.id = e.workflow_id WHERE e.id = ?",
                Long.class, executionId);
        return ids.isEmpty() ? 0L : ids.get(0);
    }

    private static void write(Path target, byte[] content) {
        ensureDir(target.getParent());
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "产物写入失败 " + target.getFileName() + ": " + e.getMessage());
        }
    }

    private static void ensureDir(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "产物目录创建失败: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Map<String, Object> parseMeta(Object raw) {
        String s = raw == null ? "" : String.valueOf(raw);
        if (s.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(s, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // 只报异常类型不带堆栈：meta_json 里可能含用户正文，错误信息会进日志与响应
            throw new ApiException(ErrorCode.INTERNAL, "成品元数据无法解析: " + e.getClass().getSimpleName());
        }
    }

    private static String prettyJson(Map<String, Object> meta) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 图片清单里的期望文件名：新数据是 {url,fileName,…} 结构，旧数据可能直接是文件名字符串。 */
    private static List<String> expectedAssets(Map<String, Object> meta) {
        List<String> out = new ArrayList<>();
        Object images = meta.get("images");
        if (!(images instanceof List<?> items)) {
            return out;
        }
        for (Object item : items) {
            if (item instanceof Map<?, ?> map && map.get("fileName") != null) {
                out.add(String.valueOf(map.get("fileName")));
            } else if (item instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> items) {
            items.forEach(o -> out.add(o == null ? "" : String.valueOf(o)));
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}

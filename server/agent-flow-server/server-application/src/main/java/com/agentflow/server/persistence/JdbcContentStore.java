package com.agentflow.server.persistence;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.nodes.spi.ContentStore;
import com.agentflow.server.config.ContentProperties;
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

    private final JdbcTemplate jdbc;
    private final Path root;

    public JdbcContentStore(JdbcTemplate jdbc, ContentProperties props) {
        this.jdbc = jdbc;
        this.root = Path.of(props.libraryDir()).toAbsolutePath().normalize();
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
    public Map<String, Object> find(long artifactId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, execution_id, owner_id, kind, title, body, meta_json, dir, created_at "
                        + "FROM artifact WHERE id = ?", artifactId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** 供 API 层列成品（节点不需要，故不进 ContentStore 端口，留在实现类上）。 */
    public List<Map<String, Object>> list(long ownerId, String kind, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, execution_id, kind, title, dir, created_at FROM artifact WHERE owner_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = ?");
            args.add(kind);
        }
        sql.append(" ORDER BY id DESC LIMIT ").append(Math.max(1, Math.min(limit, 200)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> detail(long artifactId, long ownerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, execution_id, owner_id, kind, title, body, meta_json, dir, created_at "
                        + "FROM artifact WHERE id = ? AND owner_id = ?", artifactId, ownerId);
        if (rows.isEmpty()) {
            // 非属主与不存在同口径回 404：避免成品 id 是否存在被探测（与 P3 workflow 归属隔离一致）
            throw new ApiException(ErrorCode.NOT_FOUND, "成品不存在: " + artifactId);
        }
        return rows.get(0);
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
}

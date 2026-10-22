package com.agentflow.nodes;

import com.agentflow.nodes.spi.ContentStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点侧单测用的 ContentStore 假实现：只记录调用与内容，不碰磁盘与数据库。
 * 真实现（含路径穿越校验、目录搬迁）由 server-application 的 JdbcContentStoreTest 覆盖。
 */
public final class FakeContentStore implements ContentStore {

    public final List<String> log = new ArrayList<>();
    public final Map<Long, Map<String, Object>> artifacts = new LinkedHashMap<>();
    public final Map<Long, Map<String, String>> files = new LinkedHashMap<>();
    public final Map<Long, List<String>> staged = new LinkedHashMap<>();
    public final Map<Long, List<String>> attached = new LinkedHashMap<>();

    private long sequence = 0;

    @Override
    public String stage(long executionId, String suggestedName, byte[] content) {
        log.add("stage:" + executionId + ":" + suggestedName + ":" + content.length);
        staged.computeIfAbsent(executionId, k -> new ArrayList<>()).add(suggestedName);
        return ".staging/" + executionId + "/" + suggestedName;
    }

    @Override
    public List<String> stagedFiles(long executionId) {
        return staged.getOrDefault(executionId, List.of());
    }

    @Override
    public long insert(long executionId, String kind, String title, String body, String metaJson) {
        log.add("insert:" + executionId + ":" + kind);
        long id = ++sequence;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("execution_id", executionId);
        row.put("kind", kind);
        row.put("title", title);
        row.put("body", body);
        row.put("meta_json", metaJson);
        artifacts.put(id, row);
        files.put(id, new LinkedHashMap<>());
        attached.put(id, new ArrayList<>());
        return id;
    }

    @Override
    public String artifactDir(long artifactId) {
        return "/fake/library/" + artifactId;
    }

    @Override
    public List<String> attachStaged(long executionId, long artifactId) {
        List<String> moved = new ArrayList<>();
        for (String name : staged.getOrDefault(executionId, List.of())) {
            moved.add(artifactId + "/assets/" + name);
        }
        attached.get(artifactId).addAll(moved);
        staged.remove(executionId);
        log.add("attach:" + executionId + ":" + moved.size());
        return moved;
    }

    @Override
    public String writeText(long artifactId, String fileName, String content) {
        log.add("write:" + artifactId + ":" + fileName + ":" + content.length());
        files.computeIfAbsent(artifactId, k -> new LinkedHashMap<>()).put(fileName, content);
        return "/fake/library/" + artifactId + "/" + fileName;
    }

    @Override
    public Map<String, Object> find(long artifactId) {
        return artifacts.getOrDefault(artifactId, Map.of());
    }
}

package com.agentflow.nodes.spi;

import java.util.List;
import java.util.Map;

/**
 * 内容库端口（纯 Java，node-adapters 侧定义）：创作产物的落库与落盘。
 * 实现在 server-application（JdbcTemplate + 本地文件系统），节点不感知存储介质——
 * 与 CredentialProvider 一样的依赖方向（server → node-adapters）。
 *
 * 生命周期约定：
 * 1. 生成期（image.cover 等）先把字节暂存到 execution 级 staging 目录，避免"图有了、成品还没编号"的悬空；
 * 2. 发布期（publish.local）登记 artifact 行并把 staging 文件收进成品目录，形成一次可追溯的交付；
 * 3. 相对路径一律由实现层做穿越校验（禁止 ../），节点只传 suggestedName。
 */
public interface ContentStore {

    /** 暂存一次执行产生的文件，返回相对路径（staging 内）。 */
    String stage(long executionId, String suggestedName, byte[] content);

    /** 列出该执行的暂存文件（相对 staging 目录的文件名）。 */
    List<String> stagedFiles(long executionId);

    /** 登记成品，返回自增 artifactId。metaJson 存结构化元数据（标签、比例、来源链接等）。 */
    long insert(long executionId, String kind, String title, String body, String metaJson);

    /** 成品目录（绝对路径），供日志与 API 回显。 */
    String artifactDir(long artifactId);

    /** 把 staging 文件搬进成品目录，返回搬迁后的相对路径列表。 */
    List<String> attachStaged(long executionId, long artifactId);

    /** 在成品目录写文本文件（article.md / meta.json），返回绝对路径。 */
    String writeText(long artifactId, String fileName, String content);

    /**
     * 文件阶段全部落成功 ⇒ 标记成品完整。
     * 与 insert 分开是刻意的：insert 拿到 id 才能定目录名，所以"有行"不等于"有货"，
     * 需要一个显式的收尾状态，否则半套数据在库里和正常成品长得一模一样。
     */
    void markComplete(long artifactId);

    /** 文件阶段失败 ⇒ 把原因钉在成品行上（可见 + 可补偿），调用方随后照常抛错。 */
    void markIncomplete(long artifactId, String reason);

    /** 按 id 取成品（含正文与元数据），不存在返回空 Map。 */
    Map<String, Object> find(long artifactId);
}

package com.agentflow.server.storage;

/**
 * 对象存储端口（S3 兼容）：成品目录的远端副本。
 *
 * 与 ContentStore 分开是刻意的：本地卷是"交付面"（写完就能读），对象存储是"副本面"（跨实例可见、可归档）。
 * 两者的状态也分开记（file_state / sync_state），混成一个状态会让"补偿成功"看起来像"备份成功"。
 */
public interface ObjectStore {

    /** 上传对象；非 2xx 抛 ApiException（DEPENDENCY_UNAVAILABLE），不带凭证。 */
    void put(String key, byte[] body, String contentType);

    /** 读取对象；不存在返回 null（读端据此回 404，不猜"是不是网络问题"）。 */
    byte[] get(String key);

    /** 对象是否存在（HEAD）。 */
    boolean exists(String key);

    /** 删除对象（幂等：不存在也算成功）。测试与"成品退场"用它清理副本。 */
    void delete(String key);

    /** 确保桶可用（HEAD 不通则建）；启动时调用一次，失败不致命。 */
    void ensureBucket();
}

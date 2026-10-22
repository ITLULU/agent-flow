-- V3: 内容库——创作成品（文章/笔记/图文包）与产物文件索引
-- 方言口径与 V1/V2 一致：VARCHAR 无界承载正文与元数据 JSON（PG 中等价 TEXT），BIGSERIAL 主键，不引 jsonb/方言函数

CREATE TABLE artifact (
    id           BIGSERIAL PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    owner_id     BIGINT NOT NULL DEFAULT 0,   -- 归属沿用 workflow.owner_id（经 execution 反查），0=系统/匿名
    kind         VARCHAR(32) NOT NULL,        -- article | note | xhs-package
    title        VARCHAR(512) NOT NULL,
    body         VARCHAR NOT NULL,            -- 成品正文（markdown 或纯文本）
    meta_json    VARCHAR,                     -- 标签/金句/封面文案/图片清单/traceId
    dir          VARCHAR(512) NOT NULL,       -- 产物目录（绝对路径），图与文本同处一目录便于打包
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_artifact_owner ON artifact (owner_id, created_at);
CREATE INDEX idx_artifact_execution ON artifact (execution_id);

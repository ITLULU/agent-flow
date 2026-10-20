-- V1: 引擎核心表（H2 PostgreSQL 模式与 PG16 双兼容：只用两者共同支持的语法）
-- 口径：dsl/output 用 VARCHAR 无界（PG 中等价 TEXT；H2 映射 CLOB），避免 jsonb 方言分歧

CREATE TABLE workflow (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    dsl         VARCHAR NOT NULL,
    version     INT NOT NULL DEFAULT 1,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE execution (
    id          BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL REFERENCES workflow (id),
    trace_id    VARCHAR(64),
    status      VARCHAR(30) NOT NULL,
    started_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE execution_node_log (
    id          BIGSERIAL PRIMARY KEY,
    execution_id BIGINT NOT NULL REFERENCES execution (id),
    node_id     VARCHAR(64) NOT NULL,
    node_type   VARCHAR(64) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    attempts    INT NOT NULL,
    duration_ms BIGINT NOT NULL,
    output_json VARCHAR,
    reason      VARCHAR(2000)
);

CREATE INDEX idx_execution_workflow ON execution (workflow_id);
CREATE INDEX idx_node_log_execution ON execution_node_log (execution_id);

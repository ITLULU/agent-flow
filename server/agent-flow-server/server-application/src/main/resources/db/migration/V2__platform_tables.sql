-- V2: 平台表——用户/凭证库 + workflow 归属与调度列（H2 PostgreSQL 模式 / PG16 双兼容）
-- 口径与 V1 一致：VARCHAR 无界承载密文封装（PG 中等价 TEXT）；不引入 jsonb/方言函数

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16) NOT NULL DEFAULT 'user',
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_credentials (
    id              BIGSERIAL PRIMARY KEY,
    owner_id        BIGINT NOT NULL,
    provider        VARCHAR(32) NOT NULL,
    name            VARCHAR(64) NOT NULL,
    secret_json_enc VARCHAR NOT NULL, -- AES-256-GCM 封装：base64(iv||ct||tag)，禁止明文落库
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, provider, name)
);

ALTER TABLE workflow ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 0; -- 0=系统/匿名（enabled=false 时期存量）
ALTER TABLE workflow ADD COLUMN schedule_cron VARCHAR(64);          -- 6 段 CronExpression 字符串
ALTER TABLE workflow ADD COLUMN schedule_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workflow ADD COLUMN next_fire_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_workflow_owner ON workflow (owner_id);
CREATE INDEX idx_workflow_schedule ON workflow (schedule_enabled, next_fire_at);
CREATE INDEX idx_credentials_owner ON user_credentials (owner_id);

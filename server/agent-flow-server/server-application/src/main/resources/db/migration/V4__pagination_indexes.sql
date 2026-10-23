-- V4: 游标分页索引——列表统一 id DESC + beforeId 过滤，复合索引避免大表回表排序

CREATE INDEX IF NOT EXISTS idx_workflow_owner_id ON workflow (owner_id, id);
CREATE INDEX IF NOT EXISTS idx_credentials_owner_id ON user_credentials (owner_id, id);
CREATE INDEX IF NOT EXISTS idx_artifact_owner_id ON artifact (owner_id, id);

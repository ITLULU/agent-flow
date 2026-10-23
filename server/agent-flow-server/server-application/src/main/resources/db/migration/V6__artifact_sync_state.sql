-- V6: 成品同步状态——把"产物只在本机卷上"变成可见、可追的状态（评审 #12 的对象存储半边）
-- 方言口径与 V3/V5 一致：VARCHAR 无界存清单 JSON，不引方言函数，H2(PostgreSQL 模式) 与 PG16 同语法可跑

ALTER TABLE artifact ADD COLUMN sync_state VARCHAR(16) NOT NULL DEFAULT 'local';
ALTER TABLE artifact ADD COLUMN sync_error VARCHAR(500);
ALTER TABLE artifact ADD COLUMN sync_manifest VARCHAR;
ALTER TABLE artifact ADD COLUMN synced_at TIMESTAMP WITH TIME ZONE;

-- local=只在本地卷；synced=对象存储里已有一套且清单核对过；failed=最近一次同步失败（原因在 sync_error）
-- 同步永远不改 file_state：本地完整性和远端副本是两件事，混成一个状态会让"补偿成功"看起来像"备份成功"
CREATE INDEX idx_artifact_sync_state ON artifact (sync_state, id);

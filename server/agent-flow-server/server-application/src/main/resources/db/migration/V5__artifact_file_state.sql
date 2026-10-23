-- V5: 成品文件状态——把"库里有行、盘上没货"从静默半套变成可见、可补偿的状态（评审 #12）
-- 方言口径与 V3 一致：VARCHAR 无界、不用方言函数，H2(PostgreSQL 模式) 与 PG16 同语法可跑

ALTER TABLE artifact ADD COLUMN file_state VARCHAR(16) NOT NULL DEFAULT 'pending';
ALTER TABLE artifact ADD COLUMN file_error VARCHAR(500);

-- pending=行已建、文件未落完；complete=落盘成功；incomplete=落盘失败，可用 repair 从库内正文/元数据重建文本
CREATE INDEX idx_artifact_file_state ON artifact (file_state, id);

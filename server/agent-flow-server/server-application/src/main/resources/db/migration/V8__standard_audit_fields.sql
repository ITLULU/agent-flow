-- V8: 四件标准审计字段——所有业务表统一具备 create_time/update_time/creator/updater
-- 方言口径沿用 V1–V7：只用 H2(PostgreSQL 模式) 与 PG16 共同支持的语法。
-- 命名统一：既有 created_at/updated_at 直接 RENAME 成标准名，不保留双轨（双轨会让"哪个是权威"再次漂移）。
-- 填充口径：create_time/update_time 由 DEFAULT CURRENT_TIMESTAMP 兜底 + Java UPDATE 显式写 update_time；
-- PG 没有 MySQL 的 ON UPDATE 子句，触发器又跨不过 H2 方言，更新时间一律应用层负责（与 V1 起的既有写法一致）。
-- creator/updater 存操作人用户名（登录主体），后台/调度线程无请求主体时落 'system'；
-- 存量行按 'system' 回填——它们确实由系统初始化/匿名期写入，不伪造人名。

-- workflow：created_at → create_time，补 update_time/creator/updater
ALTER TABLE workflow RENAME COLUMN created_at TO create_time;
ALTER TABLE workflow ADD COLUMN update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE workflow ADD COLUMN creator VARCHAR(64) NOT NULL DEFAULT 'system';
ALTER TABLE workflow ADD COLUMN updater VARCHAR(64) NOT NULL DEFAULT 'system';

-- execution：started_at/finished_at 是业务时间戳，不顶替审计列
ALTER TABLE execution ADD COLUMN create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE execution ADD COLUMN update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE execution ADD COLUMN creator VARCHAR(64) NOT NULL DEFAULT 'system';
ALTER TABLE execution ADD COLUMN updater VARCHAR(64) NOT NULL DEFAULT 'system';

-- execution_node_log
ALTER TABLE execution_node_log ADD COLUMN create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE execution_node_log ADD COLUMN update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE execution_node_log ADD COLUMN creator VARCHAR(64) NOT NULL DEFAULT 'system';
ALTER TABLE execution_node_log ADD COLUMN updater VARCHAR(64) NOT NULL DEFAULT 'system';

-- users
ALTER TABLE users RENAME COLUMN created_at TO create_time;
ALTER TABLE users ADD COLUMN update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE users ADD COLUMN creator VARCHAR(64) NOT NULL DEFAULT 'system';
ALTER TABLE users ADD COLUMN updater VARCHAR(64) NOT NULL DEFAULT 'system';

-- user_credentials：updated_at → update_time，补 create_time/creator/updater
ALTER TABLE user_credentials RENAME COLUMN updated_at TO update_time;
ALTER TABLE user_credentials ADD COLUMN create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE user_credentials ADD COLUMN creator VARCHAR(64) NOT NULL DEFAULT 'system';
ALTER TABLE user_credentials ADD COLUMN updater VARCHAR(64) NOT NULL DEFAULT 'system';

-- artifact：created_at → create_time（idx_artifact_owner 引用随重命名自动跟随）
ALTER TABLE artifact RENAME COLUMN created_at TO create_time;
ALTER TABLE artifact ADD COLUMN update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE artifact ADD COLUMN creator VARCHAR(64) NOT NULL DEFAULT 'system';
ALTER TABLE artifact ADD COLUMN updater VARCHAR(64) NOT NULL DEFAULT 'system';

-- execution_lease / quota_gate：运行时账本同样要有标准列（配额仲裁的每一次插/续都要可追责）
ALTER TABLE execution_lease ADD COLUMN create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE execution_lease ADD COLUMN update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE execution_lease ADD COLUMN creator VARCHAR(64) NOT NULL DEFAULT 'system';
ALTER TABLE execution_lease ADD COLUMN updater VARCHAR(64) NOT NULL DEFAULT 'system';

ALTER TABLE quota_gate ADD COLUMN create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE quota_gate ADD COLUMN update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE quota_gate ADD COLUMN creator VARCHAR(64) NOT NULL DEFAULT 'system';
ALTER TABLE quota_gate ADD COLUMN updater VARCHAR(64) NOT NULL DEFAULT 'system';

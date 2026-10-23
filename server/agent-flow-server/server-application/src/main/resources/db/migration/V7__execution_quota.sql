-- V7: 跨实例执行配额（评审 P7-3 #11）——把"每实例各数自己的 16 槽"变成全集群一份、可仲裁的账本
-- 方言口径沿用 V1–V6：只用 H2(PostgreSQL 模式) 与 PG16 共同支持的语法；过期时间由 Java 侧算好写入，
-- 因为 H2 与 PG 的时间运算函数写法不同，一旦用方言函数，测试绿的生产蓝。

-- 租约 = 一次正在占用集群执行额度的执行。不加指向 execution 的外键：租约是短时状态，
-- 执行记录属于长期台账（将来要归档/分区），把两者的生命周期绑死会让归档动作卡在配额表上。
CREATE TABLE execution_lease (
    execution_id BIGINT PRIMARY KEY,
    owner_id     BIGINT NOT NULL DEFAULT 0,
    instance_id  VARCHAR(64) NOT NULL,
    acquired_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_lease_owner ON execution_lease (owner_id);
CREATE INDEX idx_lease_expiry ON execution_lease (expires_at);

-- 单行哨兵："数一遍名额 → 插一条租约"这两步本身不原子，两个实例会在同一瞬间各自数出"还剩 1 个"。
-- 所有配额决策先 FOR UPDATE 这一行，把仲裁段串行化。行被误删时租约照样能插进去——上限会静默失效，
-- 所以配额服务启动时必须校验这行存在（见 JdbcExecutionQuota 构造器）。
CREATE TABLE quota_gate (
    id INT PRIMARY KEY
);

INSERT INTO quota_gate (id) VALUES (1);

-- V9: 工作流逻辑删除（画布上要能删，但历史不能跟着一起蒸发）。
--
-- 为什么逻辑删而不是物理删：execution / execution_node_log / artifact 都以 workflow_id 为线索，
-- 成品还带 assets 文件落在盘上。物理删一条流水线 = 抹掉它全部执行记录的可追溯性，
-- 而且 execution.workflow_id 有外键，真删还得先级联清三张表——那不是"删除"，那是"销毁"。
--
-- deleted 默认 FALSE：存量行都是"未删"，不需要回填判断。
-- deleted_time 只在 deleted=TRUE 时写：留给将来真要清理磁盘/数据库时的"保留期到期"判据。
ALTER TABLE workflow ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workflow ADD COLUMN deleted_time TIMESTAMP WITH TIME ZONE;

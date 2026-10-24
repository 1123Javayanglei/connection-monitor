-- connection-monitor v1 -> v2 数据库迁移脚本
--
-- 背景：v1 的 monitor_pool_latest 以 (instance_id, pool_name) 为唯一键，
-- 同一实例中多个同名连接池（不同 DataSource Bean）会互相覆盖；
-- v2 改为 (instance_id, pool_id) 唯一，并补充多数据源识别列。
--
-- 执行前请备份相关表；脚本幂等性有限，建议在低峰期一次性执行。

-- 1. monitor_pool_latest：补充新列
ALTER TABLE monitor_pool_latest
    ADD COLUMN data_source_id VARCHAR(256) NOT NULL DEFAULT '' AFTER pool_name,
    ADD COLUMN data_source_bean_name VARCHAR(256) NULL AFTER data_source_id,
    ADD COLUMN pool_type VARCHAR(32) NULL AFTER pool_name,
    ADD COLUMN pool_version VARCHAR(64) NULL AFTER pool_type,
    ADD COLUMN metrics_availability VARCHAR(32) NULL AFTER pool_version;

-- 2. 回填：v1 行以 instance_id + ':' + pool_name 生成稳定 pool_id
--    （旧数据假设“池名 = Bean 名”；接入新版客户端后新行会携带准确的 Bean 名与类型）
UPDATE monitor_pool_latest
SET pool_id   = CONCAT(instance_id, ':', pool_name),
    data_source_id = CONCAT(instance_id, ':', pool_name)
WHERE pool_id IS NULL OR pool_id = '';

-- 3. 删除旧唯一键，建立新唯一键
--    （键名以实际环境为准，可用 SHOW CREATE TABLE monitor_pool_latest 查看）
ALTER TABLE monitor_pool_latest
    DROP INDEX uk_instance_pool_name;
ALTER TABLE monitor_pool_latest
    ADD UNIQUE KEY uk_instance_pool (instance_id, pool_id);

-- 4. monitor_pool_history：历史数据按相同规则回填 pool_id（若 v1 历史表使用 pool_name 关联）
--    v1 表结构若为 (instance_id, pool_name, ...)，建议新建 v2 历史表并迁移：
CREATE TABLE IF NOT EXISTS monitor_pool_history_v2 AS
SELECT id, instance_id,
       CONCAT(instance_id, ':', pool_name) AS pool_id,
       NULL AS pool_type,
       active_connections, idle_connections, total_connections,
       max_connections, min_idle_connections,
       threads_awaiting_connection, sample_time
FROM monitor_pool_history;

-- 5. monitor_instance：v2 新增 JVM 指标列（v1 若无该表则直接执行 schema-mysql.sql 建表）
-- ALTER TABLE monitor_instance ADD COLUMN heap_used_bytes BIGINT NULL;  -- 其余列同理按需补充

-- 6. 验证
SELECT instance_id, pool_id, pool_name, pool_type, metrics_availability
FROM monitor_pool_latest LIMIT 20;

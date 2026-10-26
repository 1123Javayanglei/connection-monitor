-- connection-monitor PostgreSQL 建表脚本
-- 与 db/schema-gaussdb.sql 结构保持一致；由 connection-monitor.storage 自动选择并执行。
-- 脚本幂等，可重复运行；如数据库版本不支持 CREATE INDEX IF NOT EXISTS，
-- 自动初始化会跳过失败索引并继续建表，也可设置 init-schema=false 后由 DBA 手工执行
-- （按需去掉索引语句中的 IF NOT EXISTS）。

CREATE TABLE IF NOT EXISTS monitor_pool_latest (
    id BIGSERIAL PRIMARY KEY,
    instance_id VARCHAR(128) NOT NULL,
    pool_id VARCHAR(256) NOT NULL,
    data_source_id VARCHAR(256) NOT NULL,
    data_source_bean_name VARCHAR(256),
    pool_name VARCHAR(256),
    pool_type VARCHAR(32),
    pool_version VARCHAR(64),
    metrics_availability VARCHAR(32),
    active_connections INT NULL,
    idle_connections INT NULL,
    total_connections INT NULL,
    max_connections INT NULL,
    min_idle_connections INT NULL,
    threads_awaiting_connection INT NULL,
    connection_timeout_ms BIGINT NULL,
    extra_metrics TEXT NULL,
    sample_time BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_instance_pool UNIQUE (instance_id, pool_id)
);

CREATE INDEX IF NOT EXISTS idx_pool_type ON monitor_pool_latest (pool_type);
CREATE INDEX IF NOT EXISTS idx_bean_name ON monitor_pool_latest (data_source_bean_name);

CREATE TABLE IF NOT EXISTS monitor_pool_history (
    id BIGSERIAL PRIMARY KEY,
    instance_id VARCHAR(128) NOT NULL,
    pool_id VARCHAR(256) NOT NULL,
    pool_type VARCHAR(32),
    active_connections INT NULL,
    idle_connections INT NULL,
    total_connections INT NULL,
    max_connections INT NULL,
    min_idle_connections INT NULL,
    threads_awaiting_connection INT NULL,
    sample_time BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_instance_pool_time
    ON monitor_pool_history (instance_id, pool_id, sample_time);

CREATE TABLE IF NOT EXISTS monitor_instance (
    instance_id VARCHAR(128) PRIMARY KEY,
    app_name VARCHAR(256),
    host VARCHAR(256),
    heap_used_bytes BIGINT NULL,
    heap_max_bytes BIGINT NULL,
    non_heap_used_bytes BIGINT NULL,
    total_thread_count INT NULL,
    peak_thread_count INT NULL,
    daemon_thread_count INT NULL,
    loaded_class_count INT NULL,
    uptime_ms BIGINT NULL,
    process_cpu_load DOUBLE PRECISION NULL,
    last_report_time BIGINT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

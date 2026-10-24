-- connection-monitor 服务端建表脚本（MySQL 5.7+ / H2 MySQL 模式通用）
-- 最新状态表：以 (instance_id, pool_id) 唯一，区分同实例下多个连接池，
-- 同名连接池（pool_name 相同）不会互相覆盖。
CREATE TABLE IF NOT EXISTS monitor_pool_latest (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    UNIQUE KEY uk_instance_pool (instance_id, pool_id),
    KEY idx_pool_type (pool_type),
    KEY idx_bean_name (data_source_bean_name)
);

-- 历史数据表：以稳定 pool_id 关联连接池（而非显示名称），
-- 某类连接池未提供的指标存 NULL，查询时按 pool_id 隔离，不会混淆 Druid/HikariCP 指标。
CREATE TABLE IF NOT EXISTS monitor_pool_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id VARCHAR(128) NOT NULL,
    pool_id VARCHAR(256) NOT NULL,
    pool_type VARCHAR(32),
    active_connections INT NULL,
    idle_connections INT NULL,
    total_connections INT NULL,
    max_connections INT NULL,
    min_idle_connections INT NULL,
    threads_awaiting_connection INT NULL,
    sample_time BIGINT NOT NULL,
    KEY idx_instance_pool_time (instance_id, pool_id, sample_time)
);

-- 实例表：JVM 指标与最近上报时间
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
    process_cpu_load DOUBLE NULL,
    last_report_time BIGINT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

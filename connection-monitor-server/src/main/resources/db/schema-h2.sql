-- connection-monitor H2 建表脚本（开发/测试默认库，MySQL 兼容模式）
-- 由 connection-monitor.storage 自动选择并执行；脚本幂等，可重复运行。

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

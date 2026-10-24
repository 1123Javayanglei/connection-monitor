-- connection-monitor 生产 MySQL 建表脚本（与 src/main/resources/schema.sql 等价，
-- 增加 InnoDB / utf8mb4 显式声明。应用启动时 schema.sql 也会幂等执行，二者任选其一。）

CREATE TABLE IF NOT EXISTS monitor_pool_latest (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id VARCHAR(128) NOT NULL COMMENT '应用实例 ID',
    pool_id VARCHAR(256) NOT NULL COMMENT '连接池唯一 ID（instanceId:beanName[:route:key]）',
    data_source_id VARCHAR(256) NOT NULL COMMENT '数据源 ID',
    data_source_bean_name VARCHAR(256) COMMENT 'Spring 数据源 Bean 名称',
    pool_name VARCHAR(256) COMMENT '连接池显示名称（可重复）',
    pool_type VARCHAR(32) COMMENT 'DRUID / HIKARI / UNKNOWN',
    pool_version VARCHAR(64) COMMENT '连接池版本',
    metrics_availability VARCHAR(32) COMMENT 'FULL / PARTIAL / UNAVAILABLE',
    active_connections INT NULL,
    idle_connections INT NULL,
    total_connections INT NULL,
    max_connections INT NULL,
    min_idle_connections INT NULL,
    threads_awaiting_connection INT NULL,
    connection_timeout_ms BIGINT NULL,
    extra_metrics TEXT NULL COMMENT '连接池扩展指标 JSON（Druid 累计计数器等）',
    sample_time BIGINT NOT NULL COMMENT '采样时间（epoch 毫秒）',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_instance_pool (instance_id, pool_id),
    KEY idx_pool_type (pool_type),
    KEY idx_bean_name (data_source_bean_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接池最新状态';

CREATE TABLE IF NOT EXISTS monitor_pool_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id VARCHAR(128) NOT NULL,
    pool_id VARCHAR(256) NOT NULL,
    pool_type VARCHAR(32),
    active_connections INT NULL COMMENT '缺失指标存 NULL，不落 0',
    idle_connections INT NULL,
    total_connections INT NULL,
    max_connections INT NULL,
    min_idle_connections INT NULL,
    threads_awaiting_connection INT NULL,
    sample_time BIGINT NOT NULL,
    KEY idx_instance_pool_time (instance_id, pool_id, sample_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接池历史趋势（按稳定 pool_id 关联）';

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '应用实例与 JVM 指标';

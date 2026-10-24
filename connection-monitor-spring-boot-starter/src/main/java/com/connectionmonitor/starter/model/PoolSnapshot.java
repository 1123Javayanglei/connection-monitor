package com.connectionmonitor.starter.model;

import java.util.List;
import java.util.Map;

/**
 * 统一连接池快照：Druid 与 HikariCP 输出同一数据结构，服务端无需为不同连接池维护两套模型。
 *
 * <p>数量类指标为整数、时间类指标为毫秒；不可用指标一律为 {@code null}，绝不使用 0 冒充。
 * {@link #metricsAvailability} 与 {@link #unavailableMetrics} 共同描述指标可用性：
 * FULL=统一指标全部可用，PARTIAL=部分不可用，UNAVAILABLE=连接池类型未知或整体不可采集。</p>
 */
public class PoolSnapshot {

    public static final String AVAILABILITY_FULL = "FULL";
    public static final String AVAILABILITY_PARTIAL = "PARTIAL";
    public static final String AVAILABILITY_UNAVAILABLE = "UNAVAILABLE";

    public static final String POOL_TYPE_DRUID = "DRUID";
    public static final String POOL_TYPE_HIKARI = "HIKARI";
    public static final String POOL_TYPE_UNKNOWN = "UNKNOWN";

    /** 连接池唯一 ID（存储与查询主键，取值与 dataSourceId 一致，同一实例内可区分不同连接池）。 */
    private String poolId;
    /** 数据源唯一 ID：instanceId + ":" + 归一化 Bean 名（必要时附加确定性后缀）。 */
    private String dataSourceId;
    /** Spring 容器中的数据源 Bean 名称（业务可读标识）。 */
    private String dataSourceBeanName;
    /** 连接池自身配置的名称；无法获取时退化为 Bean 名等稳定标识。 */
    private String poolName;
    /** 连接池类型：DRUID / HIKARI / UNKNOWN。 */
    private String poolType;
    /** 连接池版本，可获取时记录，否则 null。 */
    private String poolVersion;
    private Integer activeConnections;
    private Integer idleConnections;
    private Integer totalConnections;
    private Integer maxConnections;
    private Integer minIdleConnections;
    private Integer threadsAwaitingConnection;
    /** 获取连接最大等待时间（毫秒）。 */
    private Long connectionTimeoutMs;
    /** 采样时间（epoch 毫秒）。 */
    private Long sampleTime;
    /** 指标可用性：FULL / PARTIAL / UNAVAILABLE。 */
    private String metricsAvailability;
    /** 不可用指标名列表（PARTIAL/UNAVAILABLE 时非空）。 */
    private List<String> unavailableMetrics;
    /** 连接池特有的扩展指标（如 Druid 累计计数器），键名与该连接池公开 API 对应。 */
    private Map<String, Long> extraMetrics;

    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId;
    }

    public String getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(String dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public String getDataSourceBeanName() {
        return dataSourceBeanName;
    }

    public void setDataSourceBeanName(String dataSourceBeanName) {
        this.dataSourceBeanName = dataSourceBeanName;
    }

    public String getPoolName() {
        return poolName;
    }

    public void setPoolName(String poolName) {
        this.poolName = poolName;
    }

    public String getPoolType() {
        return poolType;
    }

    public void setPoolType(String poolType) {
        this.poolType = poolType;
    }

    public String getPoolVersion() {
        return poolVersion;
    }

    public void setPoolVersion(String poolVersion) {
        this.poolVersion = poolVersion;
    }

    public Integer getActiveConnections() {
        return activeConnections;
    }

    public void setActiveConnections(Integer activeConnections) {
        this.activeConnections = activeConnections;
    }

    public Integer getIdleConnections() {
        return idleConnections;
    }

    public void setIdleConnections(Integer idleConnections) {
        this.idleConnections = idleConnections;
    }

    public Integer getTotalConnections() {
        return totalConnections;
    }

    public void setTotalConnections(Integer totalConnections) {
        this.totalConnections = totalConnections;
    }

    public Integer getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(Integer maxConnections) {
        this.maxConnections = maxConnections;
    }

    public Integer getMinIdleConnections() {
        return minIdleConnections;
    }

    public void setMinIdleConnections(Integer minIdleConnections) {
        this.minIdleConnections = minIdleConnections;
    }

    public Integer getThreadsAwaitingConnection() {
        return threadsAwaitingConnection;
    }

    public void setThreadsAwaitingConnection(Integer threadsAwaitingConnection) {
        this.threadsAwaitingConnection = threadsAwaitingConnection;
    }

    public Long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(Long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public Long getSampleTime() {
        return sampleTime;
    }

    public void setSampleTime(Long sampleTime) {
        this.sampleTime = sampleTime;
    }

    public String getMetricsAvailability() {
        return metricsAvailability;
    }

    public void setMetricsAvailability(String metricsAvailability) {
        this.metricsAvailability = metricsAvailability;
    }

    public List<String> getUnavailableMetrics() {
        return unavailableMetrics;
    }

    public void setUnavailableMetrics(List<String> unavailableMetrics) {
        this.unavailableMetrics = unavailableMetrics;
    }

    public Map<String, Long> getExtraMetrics() {
        return extraMetrics;
    }

    public void setExtraMetrics(Map<String, Long> extraMetrics) {
        this.extraMetrics = extraMetrics;
    }

    @Override
    public String toString() {
        return "PoolSnapshot{poolId='" + poolId + "', poolType=" + poolType
                + ", active=" + activeConnections + ", idle=" + idleConnections
                + ", total=" + totalConnections + ", max=" + maxConnections
                + ", pending=" + threadsAwaitingConnection + ", availability=" + metricsAvailability + "}";
    }
}

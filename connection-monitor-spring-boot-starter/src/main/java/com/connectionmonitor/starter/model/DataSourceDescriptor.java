package com.connectionmonitor.starter.model;

/**
 * 数据源描述符：一个独立连接池实例的唯一标识信息。
 *
 * <p>ID 生成规则：{@code dataSourceId = instanceId + ":" + normalizedBeanName}；
 * 通过路由数据源间接发现的底层池追加确定性后缀 {@code :route:<key>}，
 * 不使用 System.identityHashCode 等跨进程不稳定值。</p>
 */
public class DataSourceDescriptor {

    private String dataSourceId;
    private String dataSourceBeanName;
    private String poolName;
    private String poolType;
    private String poolVersion;
    private String instanceId;

    public DataSourceDescriptor() {
    }

    public DataSourceDescriptor(String dataSourceId, String dataSourceBeanName, String poolName,
                                String poolType, String poolVersion, String instanceId) {
        this.dataSourceId = dataSourceId;
        this.dataSourceBeanName = dataSourceBeanName;
        this.poolName = poolName;
        this.poolType = poolType;
        this.poolVersion = poolVersion;
        this.instanceId = instanceId;
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

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    public String toString() {
        return "DataSourceDescriptor{dataSourceId='" + dataSourceId + "', beanName='" + dataSourceBeanName
                + "', poolName='" + poolName + "', poolType=" + poolType + ", poolVersion=" + poolVersion + "}";
    }
}

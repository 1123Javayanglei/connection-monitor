package com.connectionmonitor.starter.discovery;

import com.connectionmonitor.starter.model.DataSourceDescriptor;

import javax.sql.DataSource;

/**
 * 一次发现周期内识别出的独立连接池：描述符 + 真实连接池实例（已解包、已按实例去重）。
 */
public class DiscoveredDataSource {

    private final DataSourceDescriptor descriptor;
    private final DataSource pool;

    public DiscoveredDataSource(DataSourceDescriptor descriptor, DataSource pool) {
        this.descriptor = descriptor;
        this.pool = pool;
    }

    public DataSourceDescriptor getDescriptor() {
        return descriptor;
    }

    public DataSource getPool() {
        return pool;
    }
}

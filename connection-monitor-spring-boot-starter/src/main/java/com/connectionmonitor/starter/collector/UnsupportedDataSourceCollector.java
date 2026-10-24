package com.connectionmonitor.starter.collector;

import com.connectionmonitor.starter.model.DataSourceDescriptor;
import com.connectionmonitor.starter.model.PoolSnapshot;
import org.springframework.core.Ordered;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

/**
 * 通用降级处理器：无法识别连接池类型（或路由数据源等无底层池可用的场景）时，
 * 仍记录该数据源本身，但所有连接池指标标记为不可用（null + UNAVAILABLE），绝不伪造为 0。
 */
public class UnsupportedDataSourceCollector implements DataSourceMetricsCollector, Ordered {

    private static final List<String> ALL_UNIFIED_METRICS = Arrays.asList(
            "activeConnections", "idleConnections", "totalConnections", "maxConnections",
            "minIdleConnections", "threadsAwaitingConnection", "connectionTimeoutMs");

    @Override
    public boolean supports(DataSource dataSource) {
        return dataSource != null;
    }

    @Override
    public PoolSnapshot collect(DataSourceDescriptor descriptor, DataSource dataSource) {
        PoolSnapshot snapshot = new PoolSnapshot();
        if (descriptor != null) {
            snapshot.setPoolId(descriptor.getDataSourceId());
            snapshot.setDataSourceId(descriptor.getDataSourceId());
            snapshot.setDataSourceBeanName(descriptor.getDataSourceBeanName());
            snapshot.setPoolName(descriptor.getPoolName());
            snapshot.setPoolVersion(descriptor.getPoolVersion());
            snapshot.setSampleTime(System.currentTimeMillis());
        }
        snapshot.setPoolType(PoolSnapshot.POOL_TYPE_UNKNOWN);
        snapshot.setMetricsAvailability(PoolSnapshot.AVAILABILITY_UNAVAILABLE);
        snapshot.setUnavailableMetrics(ALL_UNIFIED_METRICS);
        return snapshot;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

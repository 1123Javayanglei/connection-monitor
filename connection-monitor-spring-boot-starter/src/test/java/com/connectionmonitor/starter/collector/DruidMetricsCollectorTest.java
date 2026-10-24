package com.connectionmonitor.starter.collector;

import com.alibaba.druid.pool.DruidDataSource;
import com.connectionmonitor.starter.model.DataSourceDescriptor;
import com.connectionmonitor.starter.model.PoolSnapshot;
import com.connectionmonitor.starter.support.TestDataSources;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Druid 采集器测试：公开统计 API、逐指标异常隔离、累计计数器进入 extraMetrics。
 */
class DruidMetricsCollectorTest {

    private final DruidMetricsCollector collector = new DruidMetricsCollector();

    private DruidDataSource druid;

    @AfterEach
    void tearDown() {
        if (druid != null) {
            druid.close();
        }
    }

    @Test
    void supportsOnlyDruidDataSource() {
        druid = TestDataSources.druid("supports", "druidSupports");
        HikariDataSource hikari = TestDataSources.hikari("h", "druidSupportsH");
        try {
            assertTrue(collector.supports(druid));
            assertFalse(collector.supports(hikari));
            assertEquals(PoolSnapshot.POOL_TYPE_DRUID, collector.detectPoolType(druid));
            assertEquals("supports", collector.detectPoolName(druid));
            assertNotNull(collector.detectPoolVersion(druid));
        } finally {
            hikari.close();
        }
    }

    @Test
    void collectsFromPublicStatisticsApi() throws SQLException {
        druid = TestDataSources.druid("stat-pool", "druidStats");
        // 模拟业务使用连接池：建立一次连接后归还，池完成初始化
        Connection connection = druid.getConnection();
        connection.close();

        DataSourceDescriptor descriptor =
                new DataSourceDescriptor("inst-1:druidDataSource", "druidDataSource",
                        "stat-pool", PoolSnapshot.POOL_TYPE_DRUID, "test", "inst-1");
        PoolSnapshot snapshot = collector.collect(descriptor, druid);

        assertEquals(PoolSnapshot.POOL_TYPE_DRUID, snapshot.getPoolType());
        assertEquals("inst-1:druidDataSource", snapshot.getPoolId());
        assertEquals(Integer.valueOf(0), snapshot.getActiveConnections());
        assertNotNull(snapshot.getIdleConnections());
        assertEquals(Integer.valueOf(8), snapshot.getMaxConnections());
        assertEquals(Integer.valueOf(1), snapshot.getMinIdleConnections());
        assertEquals(Long.valueOf(5000L), snapshot.getConnectionTimeoutMs());
        assertEquals(PoolSnapshot.AVAILABILITY_FULL, snapshot.getMetricsAvailability());
        // 统一映射：pooling → idle，maxActive → max
        assertTrue(snapshot.getUnavailableMetrics().isEmpty());
        // 累计计数器只出现在 extraMetrics，不会冒充当前连接数
        assertNotNull(snapshot.getExtraMetrics().get("connectCount"));
        assertNotNull(snapshot.getExtraMetrics().get("initialSize"));
        assertEquals(Long.valueOf(1L), snapshot.getExtraMetrics().get("initialSize"));
    }

    @Test
    void singleMetricFailureIsIsolated() throws SQLException {
        druid = new BrokenActiveCountDruidDataSource();
        druid.setDriverClassName("org.h2.Driver");
        druid.setUrl(TestDataSources.h2Url("druidBroken"));
        druid.setUsername("sa");
        druid.setPassword("");
        druid.setInitialSize(1);
        druid.setMinIdle(1);
        druid.setMaxActive(8);
        druid.setMaxWait(5000);
        Connection connection = druid.getConnection();
        connection.close();

        DataSourceDescriptor descriptor =
                new DataSourceDescriptor("inst-1:broken", "brokenDataSource",
                        "broken-pool", PoolSnapshot.POOL_TYPE_DRUID, null, "inst-1");
        PoolSnapshot snapshot = collector.collect(descriptor, druid);

        assertEquals(PoolSnapshot.AVAILABILITY_PARTIAL, snapshot.getMetricsAvailability());
        assertNull(snapshot.getActiveConnections());
        assertTrue(snapshot.getUnavailableMetrics().contains("activeConnections"));
        // 其他指标继续采集
        assertEquals(Integer.valueOf(8), snapshot.getMaxConnections());
        assertEquals(Integer.valueOf(1), snapshot.getMinIdleConnections());
        assertNull(snapshot.getTotalConnections(), "依赖 active 的 total 一并标记不可用");
        assertTrue(snapshot.getUnavailableMetrics().contains("totalConnections"));
    }

    /** 模拟版本差异或统计器损坏：activeCount 读取抛异常。 */
    static class BrokenActiveCountDruidDataSource extends DruidDataSource {
        private static final long serialVersionUID = 1L;

        @Override
        public int getActiveCount() {
            throw new IllegalStateException("simulated druid statistic failure");
        }
    }
}

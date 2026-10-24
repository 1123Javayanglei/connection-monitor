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
 * HikariCP 采集器测试：MXBean 公开 API、未启动池降级为 PARTIAL（null 而非 0）。
 */
class HikariMetricsCollectorTest {

    private final HikariMetricsCollector collector = new HikariMetricsCollector();

    private HikariDataSource hikari;

    @AfterEach
    void tearDown() {
        if (hikari != null) {
            hikari.close();
        }
    }

    @Test
    void supportsOnlyHikariDataSource() {
        hikari = TestDataSources.hikari("supports", "hikariSupports");
        DruidDataSource druid = TestDataSources.druid("d", "hikariSupportsD");
        try {
            assertTrue(collector.supports(hikari));
            assertFalse(collector.supports(druid));
            assertEquals(PoolSnapshot.POOL_TYPE_HIKARI, collector.detectPoolType(hikari));
            assertEquals("supports", collector.detectPoolName(hikari));
        } finally {
            druid.close();
        }
    }

    @Test
    void unstartedPoolDegradesToPartialWithNullRuntimeMetrics() {
        hikari = TestDataSources.hikari("cold-pool", "hikariCold");

        DataSourceDescriptor descriptor =
                new DataSourceDescriptor("inst-1:hikariDataSource", "hikariDataSource",
                        "cold-pool", PoolSnapshot.POOL_TYPE_HIKARI, "3.4.5", "inst-1");
        PoolSnapshot snapshot = collector.collect(descriptor, hikari);

        assertEquals(PoolSnapshot.AVAILABILITY_PARTIAL, snapshot.getMetricsAvailability());
        // 配置指标可读
        assertEquals(Integer.valueOf(10), snapshot.getMaxConnections());
        assertEquals(Integer.valueOf(2), snapshot.getMinIdleConnections());
        assertEquals(Long.valueOf(3000L), snapshot.getConnectionTimeoutMs());
        // 运行时指标为 null，绝不伪造 0
        assertNull(snapshot.getActiveConnections());
        assertNull(snapshot.getIdleConnections());
        assertNull(snapshot.getTotalConnections());
        assertNull(snapshot.getThreadsAwaitingConnection());
        assertTrue(snapshot.getUnavailableMetrics().contains("activeConnections"));
        assertTrue(snapshot.getUnavailableMetrics().contains("threadsAwaitingConnection"));
    }

    @Test
    void startedPoolReportsRuntimeMetrics() throws SQLException {
        hikari = TestDataSources.hikari("hot-pool", "hikariHot");
        Connection connection = hikari.getConnection();
        connection.close();

        DataSourceDescriptor descriptor =
                new DataSourceDescriptor("inst-1:hikariDataSource", "hikariDataSource",
                        "hot-pool", PoolSnapshot.POOL_TYPE_HIKARI, null, "inst-1");
        PoolSnapshot snapshot = collector.collect(descriptor, hikari);

        assertEquals(PoolSnapshot.AVAILABILITY_FULL, snapshot.getMetricsAvailability());
        assertNotNull(snapshot.getTotalConnections());
        assertTrue(snapshot.getTotalConnections() >= 1);
        assertEquals(Integer.valueOf(0), snapshot.getActiveConnections());
        assertNotNull(snapshot.getIdleConnections());
    }
}

package com.connectionmonitor.starter.scenario;

import com.connectionmonitor.starter.model.MetricsReport;
import com.connectionmonitor.starter.model.PoolSnapshot;
import com.connectionmonitor.starter.support.ReportCaptureServer;
import com.connectionmonitor.starter.support.TestDataSources;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 F：AbstractRoutingDataSource。
 * 期望：路由数据源本身被记录为 UNKNOWN（指标不可用、不伪造 0）；
 * 底层两个连接池各采集一次（与 Bean 直接注册的同一实例去重，不重复计入总量）；
 * 全程不触发路由选择、不创建连接。
 */
@SpringBootTest(
        classes = ScenarioF_RoutingDataSourceTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=scenario-f",
                "connection.monitor.instance-id=scenario-f-instance",
                "connection.monitor.server-url=http://127.0.0.1:19086",
                "connection.monitor.report-interval-ms=600",
                "connection.monitor.report-initial-delay-ms=400"
        })
class ScenarioF_RoutingDataSourceTest {

    private static final int CAPTURE_PORT = 19086;

    private static ReportCaptureServer server;

    @Autowired
    private ApplicationContext context;

    @BeforeAll
    static void startCaptureServer() throws Exception {
        server = ReportCaptureServer.start(CAPTURE_PORT);
    }

    @AfterAll
    static void stopCaptureServer() {
        if (server != null) {
            server.close();
        }
    }

    @SpringBootApplication
    static class App {

        @Bean
        public DataSource masterDataSource() {
            return TestDataSources.hikari("master-pool", "scenario-f-master");
        }

        @Bean
        public DataSource slaveDataSource() {
            return TestDataSources.hikari("slave-pool", "scenario-f-slave");
        }

        @Bean
        public DataSource routingDataSource() {
            AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
                @Override
                protected Object determineCurrentLookupKey() {
                    return "master";
                }
            };
            Map<Object, Object> targets = new HashMap<Object, Object>();
            targets.put("master", masterDataSource());
            targets.put("slave", slaveDataSource());
            routing.setTargetDataSources(targets);
            routing.setDefaultTargetDataSource(masterDataSource());
            return routing;
        }
    }

    @Test
    void routingRecordedOnceWithDedupedUnderlyingPools() throws Exception {
        // 业务侧正常使用主从两个池
        openAndClose(context.getBean("masterDataSource", DataSource.class));
        openAndClose(context.getBean("slaveDataSource", DataSource.class));

        MetricsReport report = server.awaitReport(20000, new java.util.function.Predicate<MetricsReport>() {
            @Override
            public boolean test(MetricsReport r) {
                return r.getPools() != null && r.getPools().size() == 3;
            }
        });

        List<PoolSnapshot> pools = report.getPools();
        Set<String> poolIds = new HashSet<String>();
        int hikariCount = 0;
        PoolSnapshot routingEntry = null;
        for (PoolSnapshot pool : pools) {
            poolIds.add(pool.getPoolId());
            if (PoolSnapshot.POOL_TYPE_HIKARI.equals(pool.getPoolType())) {
                hikariCount++;
            }
            if (pool.getPoolId().endsWith(":routingDataSource")) {
                routingEntry = pool;
            }
        }
        assertEquals(3, poolIds.size(), "poolId 不得重复");
        assertEquals(2, hikariCount, "路由与底层池同时存在时，真实连接池只统计 2 个");

        // 路由数据源本身：明确降级行为——UNKNOWN、指标 null、不可用
        assertNotNull(routingEntry);
        assertEquals(PoolSnapshot.POOL_TYPE_UNKNOWN, routingEntry.getPoolType());
        assertEquals(PoolSnapshot.AVAILABILITY_UNAVAILABLE, routingEntry.getMetricsAvailability());
        assertNull(routingEntry.getActiveConnections());
        assertNull(routingEntry.getIdleConnections());
        assertNull(routingEntry.getMaxConnections());

        // 底层池健康上报
        PoolSnapshot master = findByPoolIdSuffix(pools, ":masterDataSource");
        assertNotNull(master);
        assertEquals(PoolSnapshot.AVAILABILITY_FULL, master.getMetricsAvailability());
        assertNotNull(master.getTotalConnections());
    }

    private static PoolSnapshot findByPoolIdSuffix(List<PoolSnapshot> pools, String suffix) {
        for (PoolSnapshot pool : pools) {
            if (pool.getPoolId().endsWith(suffix)) {
                return pool;
            }
        }
        return null;
    }

    private void openAndClose(DataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.close();
    }
}

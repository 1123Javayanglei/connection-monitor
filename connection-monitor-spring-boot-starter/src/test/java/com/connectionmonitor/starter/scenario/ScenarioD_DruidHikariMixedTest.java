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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 D：Druid 与 HikariCP 混合。
 * 期望：两种连接池同时运行，同一份报文内以统一 PoolSnapshot 结构上报，
 * poolType 可区分，指标语义一致（服务端无需两套页面/接口）。
 */
@SpringBootTest(
        classes = ScenarioD_DruidHikariMixedTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=scenario-d",
                "connection.monitor.instance-id=scenario-d-instance",
                "connection.monitor.server-url=http://127.0.0.1:19084",
                "connection.monitor.report-interval-ms=600",
                "connection.monitor.report-initial-delay-ms=400"
        })
class ScenarioD_DruidHikariMixedTest {

    private static final int CAPTURE_PORT = 19084;

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
        public DataSource druidDataSource() {
            return TestDataSources.druid("druid-pool", "scenario-d-druid");
        }

        @Bean
        public DataSource hikariDataSource() {
            return TestDataSources.hikari("hikari-pool", "scenario-d-hikari");
        }
    }

    @Test
    void druidAndHikariReportedInUnifiedStructure() throws Exception {
        openAndClose(context.getBean("druidDataSource", DataSource.class));
        openAndClose(context.getBean("hikariDataSource", DataSource.class));

        MetricsReport report = server.awaitReport(20000, new java.util.function.Predicate<MetricsReport>() {
            @Override
            public boolean test(MetricsReport r) {
                if (r.getPools() == null || r.getPools().size() != 2) {
                    return false;
                }
                int druidFull = 0;
                int hikariFull = 0;
                for (PoolSnapshot pool : r.getPools()) {
                    boolean full = PoolSnapshot.AVAILABILITY_FULL.equals(pool.getMetricsAvailability());
                    if (PoolSnapshot.POOL_TYPE_DRUID.equals(pool.getPoolType()) && full) {
                        druidFull++;
                    }
                    if (PoolSnapshot.POOL_TYPE_HIKARI.equals(pool.getPoolType()) && full) {
                        hikariFull++;
                    }
                }
                return druidFull == 1 && hikariFull == 1;
            }
        });

        for (PoolSnapshot pool : report.getPools()) {
            assertTrue(pool.getPoolId().startsWith("scenario-d-instance:"));
            if (PoolSnapshot.POOL_TYPE_DRUID.equals(pool.getPoolType())) {
                assertEquals("druid-pool", pool.getPoolName());
                assertNotNull(pool.getPoolVersion());
                assertNotNull(pool.getExtraMetrics());
                assertTrue(pool.getExtraMetrics().containsKey("connectCount"));
            } else {
                assertEquals("hikari-pool", pool.getPoolName());
                assertNotNull(pool.getTotalConnections(), "已启动的 Hikari 池应有运行时指标");
            }
        }
    }

    private void openAndClose(DataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.close();
    }
}

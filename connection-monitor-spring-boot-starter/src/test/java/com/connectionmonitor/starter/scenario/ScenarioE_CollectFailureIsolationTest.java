package com.connectionmonitor.starter.scenario;

import com.alibaba.druid.pool.DruidDataSource;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 E：连接池采集异常隔离。
 * 期望：一个连接池统计器损坏时，仅该池对应指标标记不可用（null，绝不伪造 0）；
 * 正常连接池持续上报；应用不因监控异常启动失败（测试能运行即证明）。
 */
@SpringBootTest(
        classes = ScenarioE_CollectFailureIsolationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=scenario-e",
                "connection.monitor.instance-id=scenario-e-instance",
                "connection.monitor.server-url=http://127.0.0.1:19085",
                "connection.monitor.report-interval-ms=600",
                "connection.monitor.report-initial-delay-ms=400"
        })
class ScenarioE_CollectFailureIsolationTest {

    private static final int CAPTURE_PORT = 19085;

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
        public DataSource healthyHikari() {
            return TestDataSources.hikari("healthy-pool", "scenario-e-good");
        }

        @Bean
        public DataSource brokenDruid() {
            BrokenDruidDataSource ds = new BrokenDruidDataSource();
            ds.setName("druid-broken");
            ds.setDriverClassName("org.h2.Driver");
            ds.setUrl(TestDataSources.h2Url("scenario-e-broken"));
            ds.setUsername("sa");
            ds.setPassword("");
            ds.setInitialSize(1);
            ds.setMinIdle(1);
            ds.setMaxActive(8);
            ds.setMaxWait(5000);
            return ds;
        }
    }

    /** 统计器损坏的 Druid：activeCount 抛异常，其余功能正常。 */
    static class BrokenDruidDataSource extends DruidDataSource {
        private static final long serialVersionUID = 1L;

        @Override
        public int getActiveCount() {
            throw new IllegalStateException("simulated statistic failure");
        }
    }

    @Test
    void brokenPoolDegradesWhileHealthyPoolKeepsReporting() throws Exception {
        openAndClose(context.getBean("healthyHikari", DataSource.class));
        openAndClose(context.getBean("brokenDruid", DataSource.class));

        MetricsReport report = server.awaitReport(20000, new java.util.function.Predicate<MetricsReport>() {
            @Override
            public boolean test(MetricsReport r) {
                if (r.getPools() == null || r.getPools().size() != 2) {
                    return false;
                }
                PoolSnapshot healthy = findByName(r, "healthy-pool");
                PoolSnapshot broken = findByName(r, "druid-broken");
                return healthy != null && broken != null
                        && PoolSnapshot.AVAILABILITY_FULL.equals(healthy.getMetricsAvailability())
                        && PoolSnapshot.AVAILABILITY_PARTIAL.equals(broken.getMetricsAvailability());
            }
        });

        PoolSnapshot broken = findByName(report, "druid-broken");
        assertNull(broken.getActiveConnections(), "损坏指标必须是 null 而不是 0");
        assertTrue(broken.getUnavailableMetrics().contains("activeConnections"));
        assertEquals(Integer.valueOf(8), broken.getMaxConnections(), "未损坏的指标继续采集");

        PoolSnapshot healthy = findByName(report, "healthy-pool");
        assertNotNull(healthy.getTotalConnections());

        // 正常连接池持续上报：稍后还能收到包含它的后续报文
        MetricsReport later = server.awaitReport(20000, new java.util.function.Predicate<MetricsReport>() {
            @Override
            public boolean test(MetricsReport r) {
                return r.getPools() != null && findByName(r, "healthy-pool") != null;
            }
        });
        assertTrue(later.getReportTime() >= report.getReportTime());
    }

    private static PoolSnapshot findByName(MetricsReport report, String name) {
        if (report.getPools() == null) {
            return null;
        }
        for (PoolSnapshot pool : report.getPools()) {
            if (name.equals(pool.getPoolName())) {
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

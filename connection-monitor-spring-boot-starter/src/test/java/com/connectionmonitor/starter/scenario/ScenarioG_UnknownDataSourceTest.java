package com.connectionmonitor.starter.scenario;

import com.connectionmonitor.starter.model.MetricsReport;
import com.connectionmonitor.starter.model.PoolSnapshot;
import com.connectionmonitor.starter.support.ReportCaptureServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 场景 G：只存在未知类型的 DataSource（无 Druid、无 HikariCP）。
 * 期望：Starter 正常启动；数据源以 UNKNOWN 记录、指标明确标记不可用（null）；
 * JVM 指标仍正常上报；页面/服务端可清晰标记未知类型。
 */
@SpringBootTest(
        classes = ScenarioG_UnknownDataSourceTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=scenario-g",
                "connection.monitor.instance-id=scenario-g-instance",
                "connection.monitor.server-url=http://127.0.0.1:19087",
                "connection.monitor.report-interval-ms=600",
                "connection.monitor.report-initial-delay-ms=400"
        })
class ScenarioG_UnknownDataSourceTest {

    private static final int CAPTURE_PORT = 19087;

    private static ReportCaptureServer server;

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
        public DataSource legacyDataSource() {
            return new LegacyDataSource();
        }
    }

    /** 某些内部 RPC 框架自带的自定义 DataSource：无连接池语义，也拿不到池指标。 */
    static class LegacyDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLFeatureNotSupportedException("legacy datasource");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("legacy datasource");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLFeatureNotSupportedException("legacy datasource");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("legacy datasource");
        }
    }

    @Test
    void unknownPoolRecordedAndJvmStillReported() {
        MetricsReport report = server.awaitReport(20000, new java.util.function.Predicate<MetricsReport>() {
            @Override
            public boolean test(MetricsReport r) {
                return r.getPools() != null && r.getPools().size() == 1 && r.getJvm() != null;
            }
        });

        assertEquals("scenario-g-instance", report.getInstanceId());
        assertNotNull(report.getJvm().getHeapUsedBytes(), "无已知连接池时 JVM 指标仍需上报");

        PoolSnapshot pool = report.getPools().get(0);
        assertEquals("scenario-g-instance:legacyDataSource", pool.getPoolId());
        assertEquals("legacyDataSource", pool.getDataSourceBeanName());
        assertEquals(PoolSnapshot.POOL_TYPE_UNKNOWN, pool.getPoolType());
        assertEquals(PoolSnapshot.AVAILABILITY_UNAVAILABLE, pool.getMetricsAvailability());
        assertNull(pool.getActiveConnections(), "未知类型指标必须是 null 而不是 0");
        assertNull(pool.getIdleConnections());
        assertNull(pool.getMaxConnections());
        assertNotNull(pool.getUnavailableMetrics());
    }
}

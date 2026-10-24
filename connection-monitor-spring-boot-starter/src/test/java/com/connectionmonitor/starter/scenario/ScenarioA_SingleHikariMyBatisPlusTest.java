package com.connectionmonitor.starter.scenario;

import com.connectionmonitor.starter.model.JvmMetrics;
import com.connectionmonitor.starter.model.MetricsReport;
import com.connectionmonitor.starter.model.PoolSnapshot;
import com.connectionmonitor.starter.support.ReportCaptureServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 A：Spring Boot + MyBatis-Plus + 单个 HikariCP 数据源。
 * 期望：页面口径即上报口径——一个 HIKARI 连接池；JVM 指标同时上报；无需任何 Mapper。
 */
@SpringBootTest(
        classes = ScenarioA_SingleHikariMyBatisPlusTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=scenario-a",
                "spring.datasource.url=jdbc:h2:mem:scenario-a;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "connection.monitor.instance-id=scenario-a-instance",
                "connection.monitor.server-url=http://127.0.0.1:19081",
                "connection.monitor.report-interval-ms=600",
                "connection.monitor.report-initial-delay-ms=400"
        })
class ScenarioA_SingleHikariMyBatisPlusTest {

    private static final int CAPTURE_PORT = 19081;

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
        // 使用 Spring Boot 自动装配的 HikariCP 数据源；MyBatis-Plus 在 classpath 上自动装配，
        // 无需为监控编写任何 Mapper / Service / 手动采集代码
    }

    @Test
    void singleHikariPoolAndJvmMetricsAreReported() throws Exception {
        // 模拟业务使用：建立一次连接让连接池完成启动（业务侧行为，监控自身不建连）
        DataSource dataSource = context.getBean(DataSource.class);
        openAndClose(dataSource);

        MetricsReport report = server.awaitReport(20000, new java.util.function.Predicate<MetricsReport>() {
            @Override
            public boolean test(MetricsReport r) {
                return r.getPools() != null && r.getPools().size() == 1
                        && PoolSnapshot.AVAILABILITY_FULL.equals(r.getPools().get(0).getMetricsAvailability());
            }
        });

        assertEquals("scenario-a-instance", report.getInstanceId());
        assertEquals("scenario-a", report.getAppName());

        List<PoolSnapshot> pools = report.getPools();
        assertEquals(1, pools.size());
        PoolSnapshot pool = pools.get(0);
        assertEquals(PoolSnapshot.POOL_TYPE_HIKARI, pool.getPoolType());
        assertNotNull(pool.getPoolId());
        assertTrue(pool.getPoolId().startsWith("scenario-a-instance:"), "poolId 应携带实例前缀");
        assertNotNull(pool.getTotalConnections());

        JvmMetrics jvm = report.getJvm();
        assertNotNull(jvm);
        assertNotNull(jvm.getHeapUsedBytes());
        assertNotNull(jvm.getTotalThreadCount());
    }

    private void openAndClose(DataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.close();
    }
}

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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 C：两个独立 HikariCP 数据源，连接池名称相同（dup-pool）。
 * 期望：两个连接池分别上报，poolId 不同、互不覆盖；
 * 本场景同时排除 MyBatis-Plus 自动装配，验证无 MyBatis-Plus 的应用也能工作。
 */
@SpringBootTest(
        classes = ScenarioC_MultiHikariSameNameTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=scenario-c",
                "spring.autoconfigure.exclude=com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
                "connection.monitor.instance-id=scenario-c-instance",
                "connection.monitor.server-url=http://127.0.0.1:19083",
                "connection.monitor.report-interval-ms=600",
                "connection.monitor.report-initial-delay-ms=400"
        })
class ScenarioC_MultiHikariSameNameTest {

    private static final int CAPTURE_PORT = 19083;

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
        public DataSource orderDataSource() {
            return TestDataSources.hikari("dup-pool", "scenario-c-order");
        }

        @Bean
        public DataSource userDataSource() {
            return TestDataSources.hikari("dup-pool", "scenario-c-user");
        }
    }

    @Test
    void sameNamePoolsKeepIndependentIds() throws Exception {
        openAndClose(context.getBean("orderDataSource", DataSource.class));
        openAndClose(context.getBean("userDataSource", DataSource.class));

        MetricsReport report = server.awaitReport(20000, new java.util.function.Predicate<MetricsReport>() {
            @Override
            public boolean test(MetricsReport r) {
                return r.getPools() != null && r.getPools().size() == 2;
            }
        });

        Set<String> poolIds = new HashSet<String>();
        for (PoolSnapshot pool : report.getPools()) {
            assertEquals(PoolSnapshot.POOL_TYPE_HIKARI, pool.getPoolType());
            assertEquals("dup-pool", pool.getPoolName(), "连接池名称保持业务配置");
            poolIds.add(pool.getPoolId());
        }
        assertEquals(2, poolIds.size(), "同名连接池必须有独立 poolId，否则服务端会互相覆盖");
        assertTrue(poolIds.contains("scenario-c-instance:orderDataSource"));
        assertTrue(poolIds.contains("scenario-c-instance:userDataSource"));
    }

    private void openAndClose(DataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.close();
    }
}

package com.connectionmonitor.starter.scenario;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.connectionmonitor.starter.model.MetricsReport;
import com.connectionmonitor.starter.model.PoolSnapshot;
import com.connectionmonitor.starter.support.ReportCaptureServer;
import com.connectionmonitor.starter.support.TestDataSources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 B：MyBatis-Plus 多数据源 + 两个独立 Druid DataSource（含 @Primary）。
 * 期望：两个 Druid 连接池都被发现并分别上报；多个 SqlSessionFactory 引用同一 DataSource 时
 * 不重复统计；非 @Primary 数据源不漏采。
 */
@SpringBootTest(
        classes = ScenarioB_MultiDruidMyBatisPlusTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=scenario-b",
                "connection.monitor.instance-id=scenario-b-instance",
                "connection.monitor.server-url=http://127.0.0.1:19082",
                "connection.monitor.report-interval-ms=600",
                "connection.monitor.report-initial-delay-ms=400"
        })
class ScenarioB_MultiDruidMyBatisPlusTest {

    private static final int CAPTURE_PORT = 19082;

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
        @Primary
        public DataSource masterDataSource() {
            return TestDataSources.druid("master-pool", "scenario-b-master");
        }

        @Bean
        public DataSource slaveDataSource() {
            return TestDataSources.druid("slave-pool", "scenario-b-slave");
        }

        @Bean
        @Primary
        public MybatisSqlSessionFactoryBean masterFactory(
                @Qualifier("masterDataSource") DataSource dataSource) {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            return factory;
        }

        @Bean
        public MybatisSqlSessionFactoryBean slaveFactory(
                @Qualifier("slaveDataSource") DataSource dataSource) {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            return factory;
        }
    }

    @Test
    void bothDruidPoolsReportedIndependently() throws Exception {
        // 模拟业务使用两个数据源
        openAndClose(context.getBean("masterDataSource", DataSource.class));
        openAndClose(context.getBean("slaveDataSource", DataSource.class));

        MetricsReport report = server.awaitReport(20000, new java.util.function.Predicate<MetricsReport>() {
            @Override
            public boolean test(MetricsReport r) {
                return r.getPools() != null && r.getPools().size() == 2
                        && allDruid(r.getPools())
                        && allFull(r.getPools());
            }
        });

        List<String> poolIds = new ArrayList<String>();
        for (PoolSnapshot pool : report.getPools()) {
            assertEquals(PoolSnapshot.POOL_TYPE_DRUID, pool.getPoolType());
            poolIds.add(pool.getPoolId());
        }
        assertTrue(poolIds.contains("scenario-b-instance:masterDataSource"));
        assertTrue(poolIds.contains("scenario-b-instance:slaveDataSource"), "非 @Primary 数据源不得漏采");

        // 多个 SqlSessionFactory 引用同一 DataSource 不重复统计：仍只有 2 个连接池
        // （若 MP 自动装配基于 @Primary 额外创建 Factory，池数也不会增加）
        int factoryCount = 0;
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null && SqlSessionFactory.class.isAssignableFrom(type)) {
                factoryCount++;
            }
        }
        assertTrue(factoryCount >= 2, "测试环境应存在多个 SqlSessionFactory，实际 " + factoryCount);
        assertEquals(2, report.getPools().size(), "SqlSessionFactory 数量与连接池数量解耦");
    }

    private static boolean allDruid(List<PoolSnapshot> pools) {
        for (PoolSnapshot pool : pools) {
            if (!PoolSnapshot.POOL_TYPE_DRUID.equals(pool.getPoolType())) {
                return false;
            }
        }
        return true;
    }

    private static boolean allFull(List<PoolSnapshot> pools) {
        for (PoolSnapshot pool : pools) {
            if (!PoolSnapshot.AVAILABILITY_FULL.equals(pool.getMetricsAvailability())) {
                return false;
            }
        }
        return true;
    }

    private void openAndClose(DataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.close();
    }
}

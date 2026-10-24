package com.connectionmonitor.starter.support;

import com.alibaba.druid.pool.DruidDataSource;
import com.zaxxer.hikari.HikariDataSource;

/** 场景测试用 H2 内存库数据源构造器。 */
public final class TestDataSources {

    private TestDataSources() {
    }

    public static String h2Url(String dbName) {
        return "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1";
    }

    public static DruidDataSource druid(String poolName, String dbName) {
        DruidDataSource ds = new DruidDataSource();
        ds.setName(poolName);
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl(h2Url(dbName));
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setInitialSize(1);
        ds.setMinIdle(1);
        ds.setMaxActive(8);
        ds.setMaxWait(5000);
        return ds;
    }

    public static HikariDataSource hikari(String poolName, String dbName) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(poolName);
        ds.setJdbcUrl(h2Url(dbName));
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(3000);
        return ds;
    }
}

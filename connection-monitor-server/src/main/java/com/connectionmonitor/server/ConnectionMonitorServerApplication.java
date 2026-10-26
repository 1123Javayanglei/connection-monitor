package com.connectionmonitor.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 连接池监控服务端。独立部署在实体机，使用 MySQL / GaussDB / PostgreSQL / H2 保存指标，
 * 自带 Thymeleaf + Bootstrap + ECharts 页面。
 */
@EnableScheduling
@SpringBootApplication
public class ConnectionMonitorServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectionMonitorServerApplication.class, args);
    }
}

package com.connectionmonitor.server.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务端存储配置（前缀 connection-monitor.storage）。
 */
@ConfigurationProperties(prefix = "connection-monitor.storage")
public class StorageProperties {

    /**
     * 数据库方言：auto（默认，按 JDBC URL / 产品名自动识别）、h2、mysql、gaussdb、postgresql，
     * 或自定义方言 Bean 的 id。
     */
    private String dialect = "auto";

    /** 启动时是否自动执行建表脚本；已由 DBA 手工建表时可关闭。 */
    private boolean initSchema = true;

    /**
     * 自定义建表脚本位置，支持 classpath: / file: 前缀，多个用英文逗号分隔。
     * 配置后优先于方言内置脚本，可用于尚未内置的数据库。
     */
    private String schemaLocation;

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public boolean isInitSchema() {
        return initSchema;
    }

    public void setInitSchema(boolean initSchema) {
        this.initSchema = initSchema;
    }

    public String getSchemaLocation() {
        return schemaLocation;
    }

    public void setSchemaLocation(String schemaLocation) {
        this.schemaLocation = schemaLocation;
    }
}

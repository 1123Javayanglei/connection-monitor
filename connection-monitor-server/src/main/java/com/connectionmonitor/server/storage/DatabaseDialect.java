package com.connectionmonitor.server.storage;

/**
 * 数据库方言描述：只负责“如何识别数据库”和“使用哪份建表脚本”。
 *
 * <p>服务端业务 SQL 统一使用 ANSI 风格，不依赖 MySQL 专有的
 * {@code ON DUPLICATE KEY UPDATE} 等语法，因此新增数据库时
 * 通常只需要增加一个方言实现和一份建表脚本，无需改动 Repository。</p>
 */
public interface DatabaseDialect {

    /** 方言唯一标识，对应 connection-monitor.storage.dialect 配置值，例如 mysql / gaussdb。 */
    String getId();

    /** 展示名称，仅用于日志。 */
    String getDisplayName();

    /**
     * 判断当前连接是否属于本方言。
     *
     * @param jdbcUrl     JDBC URL，可能为 null
     * @param productName DatabaseMetaData#getDatabaseProductName()，可能为 null
     */
    boolean matches(String jdbcUrl, String productName);

    /** 内置建表脚本的 classpath 路径，例如 db/schema-gaussdb.sql。 */
    String getSchemaResource();
}

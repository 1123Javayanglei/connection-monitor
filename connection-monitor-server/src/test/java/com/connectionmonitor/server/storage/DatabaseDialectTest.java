package com.connectionmonitor.server.storage;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库方言识别与建表脚本存在性测试。
 */
class DatabaseDialectTest {

    @Test
    void detectsH2EvenWhenUrlUsesPostgresqlMode() {
        assertEquals("h2", id("jdbc:h2:mem:test;MODE=PostgreSQL", "H2"));
    }

    @Test
    void detectsMysqlAndMariaDb() {
        assertEquals("mysql", id("jdbc:mysql://db:3306/monitor", "MySQL"));
        assertEquals("mysql", id("jdbc:mariadb://db:3306/monitor", "MariaDB"));
    }

    @Test
    void detectsGaussDbByUrlEvenWhenProductNameIsPostgresql() {
        assertEquals("gaussdb", id("jdbc:gaussdb://db:8000/monitor", "PostgreSQL"));
    }

    @Test
    void detectsOpenGaussByProductName() {
        assertEquals("gaussdb", id(null, "openGauss"));
        assertEquals("gaussdb", id("jdbc:opengauss://db:5432/monitor", "openGauss"));
    }

    @Test
    void detectsGaussDbDwsProductName() {
        assertEquals("gaussdb", id(null, "GaussDB(DWS)"));
    }

    @Test
    void detectsPostgresql() {
        assertEquals("postgresql", id("jdbc:postgresql://db:5432/monitor", "PostgreSQL"));
    }

    @Test
    void unknownDatabaseReturnsNull() {
        assertNull(DatabaseDialectResolver.detect("jdbc:oracle:thin:@db:1521:orcl", "Oracle"));
        // GaussDB 100（Oracle 兼容）不是 PostgreSQL 兼容方言，不应误用 schema-gaussdb.sql。
        assertNull(DatabaseDialectResolver.detect("jdbc:zenith:@db:1888", "GaussDB"));
        assertNull(DatabaseDialectResolver.detect(null, null));
    }

    @Test
    void everyBuiltInDialectHasSchemaScript() throws Exception {
        for (DatabaseDialect dialect : DatabaseDialects.builtIn()) {
            InputStream stream = getClass().getClassLoader().getResourceAsStream(dialect.getSchemaResource());
            assertNotNull(stream, "缺少建表脚本: " + dialect.getSchemaResource());
            try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name())) {
                String sql = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                assertTrue(sql.contains("monitor_pool_latest"), dialect.getId() + " 脚本缺少最新状态表");
                assertTrue(sql.contains("monitor_pool_history"), dialect.getId() + " 脚本缺少历史表");
                assertTrue(sql.contains("monitor_instance"), dialect.getId() + " 脚本缺少实例表");
            }
        }
    }

    private static String id(String jdbcUrl, String productName) {
        DatabaseDialect dialect = DatabaseDialectResolver.detect(jdbcUrl, productName);
        return dialect == null ? null : dialect.getId();
    }
}

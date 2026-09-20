package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储段表 DDL 双方言守卫（工单 0478 BE7，第 30 表）。
 */
class StoreSegmentDdlTest {

    @Test
    void mysqlSchemaDefinesStoreSegment() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS store_segment"), "MySQL 应含存储段表");
        assertTrue(ddl.contains("UNIQUE KEY uk_store_segment_id (segment_id)"), "MySQL 应含段 id 唯一键");
        assertTrue(ddl.contains("工单 0478 BE7"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesStoreSegment() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS store_segment"), "PG 应含存储段表");
        assertTrue(ddl.contains("CONSTRAINT uk_store_segment_id UNIQUE (segment_id)"), "PG 应含段 id 唯一约束");
        assertTrue(ddl.contains("COMMENT ON TABLE store_segment IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

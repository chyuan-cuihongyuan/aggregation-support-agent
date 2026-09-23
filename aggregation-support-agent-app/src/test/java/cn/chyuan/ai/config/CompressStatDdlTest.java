package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩统计表 DDL 双方言守卫（工单 0592 BR7，第 34 表）。
 */
class CompressStatDdlTest {

    @Test
    void mysqlSchemaDefinesCompressStat() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS compress_stat"), "MySQL 应含压缩统计表");
        assertTrue(ddl.contains("KEY idx_compress_stat_scene (scene, sample_at)"), "MySQL 应含场景索引");
        assertTrue(ddl.contains("工单 0592 BR7"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesCompressStat() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS compress_stat"), "PG 应含压缩统计表");
        assertTrue(ddl.contains("idx_compress_stat_scene ON compress_stat"), "PG 应含场景索引");
        assertTrue(ddl.contains("COMMENT ON TABLE compress_stat IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

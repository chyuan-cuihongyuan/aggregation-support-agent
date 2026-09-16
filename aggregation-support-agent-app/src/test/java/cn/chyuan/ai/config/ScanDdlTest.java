package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫描发现表 DDL 双方言守卫（工单 0457 BC7，第 29 表）。
 */
class ScanDdlTest {

    @Test
    void mysqlSchemaDefinesScanFinding() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS scan_finding"), "MySQL 应含扫描发现表");
        assertTrue(ddl.contains("UNIQUE KEY uk_scan_finding_fp (fingerprint)"), "MySQL 应含指纹唯一键");
        assertTrue(ddl.contains("工单 0457 BC7"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesScanFinding() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS scan_finding"), "PG 应含扫描发现表");
        assertTrue(ddl.contains("CONSTRAINT uk_scan_finding_fp UNIQUE (fingerprint)"), "PG 应含指纹唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE scan_finding IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

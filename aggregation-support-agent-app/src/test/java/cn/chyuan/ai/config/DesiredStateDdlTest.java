package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期望态状态表 DDL 双方言守卫（工单 0677 CB7，第 35 表）。
 */
class DesiredStateDdlTest {

    @Test
    void mysqlSchemaDefinesDesiredState() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS desired_state"), "MySQL 应含期望态状态表");
        assertTrue(ddl.contains("UNIQUE KEY uk_desired_state_rid (resource_type, resource_id)"), "MySQL 应含资源唯一键");
        assertTrue(ddl.contains("工单 0677 CB7"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesDesiredState() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS desired_state"), "PG 应含期望态状态表");
        assertTrue(ddl.contains("uk_desired_state_rid ON desired_state"), "PG 应含资源唯一索引");
        assertTrue(ddl.contains("COMMENT ON TABLE desired_state IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

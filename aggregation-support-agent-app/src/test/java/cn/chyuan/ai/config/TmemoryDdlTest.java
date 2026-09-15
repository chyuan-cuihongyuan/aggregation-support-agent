package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时序记忆事实边表 DDL 双方言守卫（工单 0362 AS1，第 22 表）。
 */
class TmemoryDdlTest {

    @Test
    void mysqlSchemaDefinesTmemoryEdge() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS tmemory_edge"), "MySQL 应含时序记忆边表");
        assertTrue(ddl.contains("UNIQUE KEY uk_tmemory_edge_id (edge_id)"), "MySQL 应含边唯一键");
        assertTrue(ddl.contains("valid_to"), "MySQL 应含事实失效时间列");
        assertTrue(ddl.contains("工单 0362 AS1"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesTmemoryEdge() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS tmemory_edge"), "PG 应含时序记忆边表");
        assertTrue(ddl.contains("CONSTRAINT uk_tmemory_edge_id UNIQUE (edge_id)"), "PG 应含边唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE tmemory_edge IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

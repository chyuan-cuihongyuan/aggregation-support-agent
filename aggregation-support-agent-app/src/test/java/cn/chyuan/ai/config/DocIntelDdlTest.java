package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档解析任务表 DDL 双方言守卫（工单 0395 AV9，第 24 表）。
 */
class DocIntelDdlTest {

    @Test
    void mysqlSchemaDefinesDocParseTask() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS doc_parse_task"), "MySQL 应含文档解析任务表");
        assertTrue(ddl.contains("UNIQUE KEY uk_doc_parse_task_id (task_id)"), "MySQL 应含任务唯一键");
        assertTrue(ddl.contains("工单 0395 AV9"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesDocParseTask() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS doc_parse_task"), "PG 应含文档解析任务表");
        assertTrue(ddl.contains("CONSTRAINT uk_doc_parse_task_id UNIQUE (task_id)"), "PG 应含任务唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE doc_parse_task IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

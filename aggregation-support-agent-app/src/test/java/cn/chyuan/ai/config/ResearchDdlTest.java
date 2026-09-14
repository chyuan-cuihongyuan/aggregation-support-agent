package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 研究任务/报告表 DDL 双方言守卫（工单 0353 AR7，第 20/21 表）。
 */
class ResearchDdlTest {

    @Test
    void mysqlSchemaDefinesResearchTables() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS research_task"), "MySQL 应含研究任务表");
        assertTrue(ddl.contains("UNIQUE KEY uk_research_task_id (task_id)"), "MySQL 应含任务唯一键");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS research_report"), "MySQL 应含研究报告表");
        assertTrue(ddl.contains("UNIQUE KEY uk_research_report_task (task_id)"), "MySQL 应含报告任务唯一键");
        assertTrue(ddl.contains("工单 0352 AR6"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesResearchTables() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS research_task"), "PG 应含研究任务表");
        assertTrue(ddl.contains("CONSTRAINT uk_research_task_id UNIQUE (task_id)"), "PG 应含任务唯一键");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS research_report"), "PG 应含研究报告表");
        assertTrue(ddl.contains("citation_rate"), "PG 应含引用对齐率列");
        assertTrue(ddl.contains("COMMENT ON TABLE research_report IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

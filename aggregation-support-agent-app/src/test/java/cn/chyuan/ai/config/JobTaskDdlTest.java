package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务表 DDL 双方言守卫（工单 0502 BH7，第 32 表）。
 */
class JobTaskDdlTest {

    @Test
    void mysqlSchemaDefinesJobTask() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS job_task"), "MySQL 应含任务表");
        assertTrue(ddl.contains("UNIQUE KEY uk_job_task_id (task_id)"), "MySQL 应含任务 id 唯一键");
        assertTrue(ddl.contains("工单 0502 BH7"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesJobTask() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS job_task"), "PG 应含任务表");
        assertTrue(ddl.contains("CONSTRAINT uk_job_task_id UNIQUE (task_id)"), "PG 应含任务 id 唯一约束");
        assertTrue(ddl.contains("COMMENT ON TABLE job_task IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

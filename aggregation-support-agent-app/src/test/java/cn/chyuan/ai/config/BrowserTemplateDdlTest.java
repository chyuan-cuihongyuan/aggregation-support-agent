package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浏览器任务模板表 DDL 双方言守卫（工单 0344 AQ6，第 19 表）。
 */
class BrowserTemplateDdlTest {

    @Test
    void mysqlSchemaDefinesBrowserTemplateTable() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS browser_task_template"), "MySQL 应含浏览器模板表");
        assertTrue(ddl.contains("UNIQUE KEY uk_browser_template_name (name)"), "MySQL 应含模板名唯一键");
        assertTrue(ddl.contains("actions_json"), "MySQL 应含动作序列列");
        assertTrue(ddl.contains("工单 0344 AQ6"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesBrowserTemplateTable() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS browser_task_template"), "PG 应含浏览器模板表");
        assertTrue(ddl.contains("CONSTRAINT uk_browser_template_name UNIQUE (name)"), "PG 应含模板名唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE browser_task_template IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

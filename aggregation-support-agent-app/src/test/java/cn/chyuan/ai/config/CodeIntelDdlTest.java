package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码编辑审计表 DDL 双方言守卫（工单 0434 AZ8，第 27 表）。
 */
class CodeIntelDdlTest {

    @Test
    void mysqlSchemaDefinesCodeintelEdit() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS codeintel_edit"), "MySQL 应含代码编辑审计表");
        assertTrue(ddl.contains("UNIQUE KEY uk_codeintel_edit_id (edit_id)"), "MySQL 应含编辑唯一键");
        assertTrue(ddl.contains("工单 0434 AZ8"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesCodeintelEdit() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS codeintel_edit"), "PG 应含代码编辑审计表");
        assertTrue(ddl.contains("CONSTRAINT uk_codeintel_edit_id UNIQUE (edit_id)"), "PG 应含编辑唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE codeintel_edit IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

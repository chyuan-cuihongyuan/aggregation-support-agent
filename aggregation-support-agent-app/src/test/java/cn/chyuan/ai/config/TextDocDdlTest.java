package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本文档表 DDL 双方言守卫（工单 0494 BG7，第 31 表）。
 */
class TextDocDdlTest {

    @Test
    void mysqlSchemaDefinesTextDoc() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS text_doc"), "MySQL 应含文本文档表");
        assertTrue(ddl.contains("UNIQUE KEY uk_text_doc_id (doc_id)"), "MySQL 应含文档 id 唯一键");
        assertTrue(ddl.contains("工单 0494 BG7"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesTextDoc() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS text_doc"), "PG 应含文本文档表");
        assertTrue(ddl.contains("CONSTRAINT uk_text_doc_id UNIQUE (doc_id)"), "PG 应含文档 id 唯一约束");
        assertTrue(ddl.contains("COMMENT ON TABLE text_doc IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

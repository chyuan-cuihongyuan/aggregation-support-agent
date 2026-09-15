package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索索引文档表 DDL 双方言守卫（工单 0404 AW9，第 25 表）。
 */
class SearchDdlTest {

    @Test
    void mysqlSchemaDefinesSearchIndexDoc() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS search_index_doc"), "MySQL 应含搜索索引文档表");
        assertTrue(ddl.contains("UNIQUE KEY uk_search_doc_id (doc_id)"), "MySQL 应含文档唯一键");
        assertTrue(ddl.contains("工单 0404 AW9"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesSearchIndexDoc() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS search_index_doc"), "PG 应含搜索索引文档表");
        assertTrue(ddl.contains("CONSTRAINT uk_search_doc_id UNIQUE (doc_id)"), "PG 应含文档唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE search_index_doc IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

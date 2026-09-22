package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分词词条表 DDL 双方言守卫（工单 0531 BK7，第 33 表）。
 */
class SegTermDdlTest {

    @Test
    void mysqlSchemaDefinesSegTerm() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS seg_term"), "MySQL 应含分词词条表");
        assertTrue(ddl.contains("UNIQUE KEY uk_seg_term_word (word)"), "MySQL 应含词条唯一键");
        assertTrue(ddl.contains("工单 0531 BK7"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesSegTerm() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS seg_term"), "PG 应含分词词条表");
        assertTrue(ddl.contains("CONSTRAINT uk_seg_term_word UNIQUE (word)"), "PG 应含词条唯一约束");
        assertTrue(ddl.contains("COMMENT ON TABLE seg_term IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

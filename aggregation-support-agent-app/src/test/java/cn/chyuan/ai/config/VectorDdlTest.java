package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量点表 DDL 双方言守卫（工单 0441 BA7，第 28 表）。
 */
class VectorDdlTest {

    @Test
    void mysqlSchemaDefinesVectorPoint() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS vector_point"), "MySQL 应含向量点表");
        assertTrue(ddl.contains("UNIQUE KEY uk_vector_point_id (point_id)"), "MySQL 应含点唯一键");
        assertTrue(ddl.contains("工单 0441 BA7"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesVectorPoint() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS vector_point"), "PG 应含向量点表");
        assertTrue(ddl.contains("CONSTRAINT uk_vector_point_id UNIQUE (point_id)"), "PG 应含点唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE vector_point IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图谱索引/社区表 DDL 双方言守卫（工单 0308 AM3，第 17/18 表）：
 * graph_index 与 graph_community 在 MySQL / PostgreSQL 两份 schema 中均有建表定义。
 */
class GraphRagDdlTest {

    @Test
    void mysqlSchemaDefinesGraphRagTables() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS graph_index"), "MySQL DDL 应含图谱索引表");
        assertTrue(ddl.contains("UNIQUE KEY uk_graph_index_id (index_id)"), "MySQL 应含索引唯一键");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS graph_community"), "MySQL DDL 应含图谱社区表");
        assertTrue(ddl.contains("UNIQUE KEY uk_graph_community (index_id, community_id, level)"), "MySQL 应含社区三级唯一键");
        assertTrue(ddl.contains("0=C0 基础，1=C1 聚合，2=C2 顶层"), "MySQL DDL 应带层级口径注释");
    }

    @Test
    void postgresqlSchemaDefinesGraphRagTables() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS graph_index"), "PG DDL 应含图谱索引表");
        assertTrue(ddl.contains("CONSTRAINT uk_graph_index_id UNIQUE (index_id)"), "PG 应含索引唯一键");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS graph_community"), "PG DDL 应含图谱社区表");
        assertTrue(ddl.contains("CONSTRAINT uk_graph_community UNIQUE (index_id, community_id, level)"), "PG 应含社区三级唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE graph_community IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索日志增列 DDL 双方言守卫（工单 0166 父子分块）：
 * rag_trace 表在 MySQL / PostgreSQL 两份 schema 中均含 parent_ids / parent_texts 列定义，
 * 且 PG 侧提供幂等存量迁移语句。
 */
class RagTraceParentColumnDdlTest {

    private static final String MYSQL_SCHEMA = "../sql/mysql-schema.sql";
    private static final String PG_SCHEMA = "../sql/postgresql-schema.sql";

    @Test
    void mysqlSchemaDefinesParentColumns() throws IOException {
        String ddl = read(MYSQL_SCHEMA);
        assertTrue(ddl.contains("parent_ids          TEXT          NULL"), "MySQL DDL 应含 parent_ids 列定义");
        assertTrue(ddl.contains("parent_texts        TEXT          NULL"), "MySQL DDL 应含 parent_texts 列定义");
        assertTrue(ddl.contains("工单 0166 父子分块"), "MySQL DDL 增列应带工单注释");
        assertTrue(ddl.contains("ALTER TABLE rag_trace ADD COLUMN parent_ids"), "MySQL 存量库迁移段应提供 ALTER 语句");
    }

    @Test
    void postgresqlSchemaDefinesParentColumns() throws IOException {
        String ddl = read(PG_SCHEMA);
        assertTrue(ddl.contains("parent_ids          TEXT,"), "PG DDL 应含 parent_ids 列定义");
        assertTrue(ddl.contains("parent_texts        TEXT,"), "PG DDL 应含 parent_texts 列定义");
        assertTrue(ddl.contains("COMMENT ON COLUMN rag_trace.parent_ids IS"), "PG DDL 增列应带列注释");
        assertTrue(ddl.contains("ALTER TABLE rag_trace ADD COLUMN IF NOT EXISTS parent_ids"),
                "PG 存量库迁移应提供幂等 ALTER 语句");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

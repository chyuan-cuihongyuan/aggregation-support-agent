package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户知识库配额表 DDL 双方言守卫（工单 0168，第 11 表）：
 * tenant_knowledge_quota 在 MySQL / PostgreSQL 两份 schema 中均有建表定义，
 * 含 tenant_id 唯一键与可空上限列（NULL=不限制）。
 */
class TenantQuotaDdlTest {

    @Test
    void mysqlSchemaDefinesQuotaTable() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS tenant_knowledge_quota"), "MySQL DDL 应含配额表");
        assertTrue(ddl.contains("max_documents INT         NULL"), "MySQL 应含 max_documents 可空上限列");
        assertTrue(ddl.contains("max_chunks    INT         NULL"), "MySQL 应含 max_chunks 可空上限列");
        assertTrue(ddl.contains("UNIQUE KEY uk_quota_tenant (tenant_id)"), "MySQL 应含租户唯一键");
        assertTrue(ddl.contains("未配置租户=不限制"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesQuotaTable() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS tenant_knowledge_quota"), "PG DDL 应含配额表");
        assertTrue(ddl.contains("max_documents INT         NULL"), "PG 应含 max_documents 可空上限列");
        assertTrue(ddl.contains("max_chunks    INT         NULL"), "PG 应含 max_chunks 可空上限列");
        assertTrue(ddl.contains("CONSTRAINT uk_quota_tenant UNIQUE (tenant_id)"), "PG 应含租户唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE tenant_knowledge_quota IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语音转写表 DDL 双方言守卫（工单 0386 AU8，第 23 表）。
 */
class SpeechDdlTest {

    @Test
    void mysqlSchemaDefinesSpeechTranscript() throws IOException {
        String ddl = read("../sql/mysql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS speech_transcript"), "MySQL 应含语音转写表");
        assertTrue(ddl.contains("UNIQUE KEY uk_speech_transcript_id (transcript_id)"), "MySQL 应含转写唯一键");
        assertTrue(ddl.contains("工单 0386 AU8"), "MySQL DDL 应带工单口径注释");
    }

    @Test
    void postgresqlSchemaDefinesSpeechTranscript() throws IOException {
        String ddl = read("../sql/postgresql-schema.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS speech_transcript"), "PG 应含语音转写表");
        assertTrue(ddl.contains("CONSTRAINT uk_speech_transcript_id UNIQUE (transcript_id)"), "PG 应含转写唯一键");
        assertTrue(ddl.contains("COMMENT ON TABLE speech_transcript IS"), "PG 应含表注释");
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

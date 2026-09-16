-- ============================================================
-- 迁移脚本: 扩大 document_metadata.mime_type 字段长度
-- 日期: 2026-06-29
-- 说明: 原 varchar(64) 不足以容纳 docx 等长 MIME 类型
--       (如 application/vnd.openxmlformats-officedocument.wordprocessingml.document 长达 71 字符)，
--       导致上传时 Data truncation 错误。扩为 varchar(128)。
-- ============================================================

ALTER TABLE `document_metadata`
    MODIFY COLUMN `mime_type` varchar(128) NOT NULL DEFAULT '' COMMENT 'MIME类型';

-- ============================================================
-- 迁移脚本: document_metadata 表增加多租户与软删除字段
-- 日期: 2026-06-02
-- 说明: 新增 tenant_id、owner_user_id、visibility、deleted_flag 字段，
--       支持 RAG 知识库的多租户隔离和逻辑删除功能
-- ============================================================

-- 新增租户ID字段
ALTER TABLE `document_metadata`
    ADD COLUMN `tenant_id` varchar(64) NOT NULL DEFAULT '' COMMENT '租户ID' AFTER `document_id`;

-- 新增文档归属用户ID字段
ALTER TABLE `document_metadata`
    ADD COLUMN `owner_user_id` varchar(64) NOT NULL DEFAULT '' COMMENT '文档归属用户ID' AFTER `tenant_id`;

-- 新增可见性字段
ALTER TABLE `document_metadata`
    ADD COLUMN `visibility` varchar(16) NOT NULL DEFAULT 'private' COMMENT '可见性: private/tenant/public' AFTER `error_message`;

-- 新增软删除标记字段
ALTER TABLE `document_metadata`
    ADD COLUMN `deleted_flag` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记: 0-未删除 1-已删除' AFTER `visibility`;

-- 新增索引
ALTER TABLE `document_metadata`
    ADD INDEX `idx_tenant_owner` (`tenant_id`, `owner_user_id`);

ALTER TABLE `document_metadata`
    ADD INDEX `idx_deleted_flag` (`deleted_flag`);

-- 迁移历史数据：将 user_id 的值回填到 owner_user_id
UPDATE `document_metadata`
SET `owner_user_id` = `user_id`,
    `tenant_id`     = 'default'
WHERE `owner_user_id` = '';

CREATE TABLE IF NOT EXISTS `document_metadata` (
  `id`               bigint unsigned NOT NULL AUTO_INCREMENT,
  `document_id`      varchar(64)     NOT NULL COMMENT '文档唯一ID',
  `tenant_id`        varchar(64)     NOT NULL DEFAULT '' COMMENT '租户ID',
  `owner_user_id`    varchar(64)     NOT NULL DEFAULT '' COMMENT '文档归属用户ID',
  `knowledge_base_id` varchar(64)    NOT NULL DEFAULT '' COMMENT '所属知识库ID',
  `knowledge_base_name` varchar(128) NOT NULL DEFAULT '' COMMENT '所属知识库名称',
  `file_name`        varchar(256)    NOT NULL COMMENT '原始文件名',
  `file_extension`   varchar(16)     NOT NULL COMMENT '文件扩展名(txt/md/pdf/docx/html)',
  `file_size`        bigint          NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
  `mime_type`        varchar(128)    NOT NULL DEFAULT '' COMMENT 'MIME类型',
  `content_hash`     char(64)        NOT NULL DEFAULT '' COMMENT '内容SHA-256哈希(同用户去重)',
  `total_chars`      int             NOT NULL DEFAULT 0 COMMENT '解析后文本字符数',
  `total_chunks`     int             NOT NULL DEFAULT 0 COMMENT '分块数量',
  `section_count`    int             NOT NULL DEFAULT 0 COMMENT '文档章节数',
  `processing_status` varchar(16)    NOT NULL DEFAULT 'processing' COMMENT '处理状态: processing/success/failed',
  `error_message`    varchar(512)    NOT NULL DEFAULT '' COMMENT '失败原因',
  `visibility`       varchar(16)     NOT NULL DEFAULT 'private' COMMENT '可见性: private/tenant/public',
  `deleted_flag`     tinyint         NOT NULL DEFAULT 0 COMMENT '删除标记: 0-未删除 1-已删除',
  `user_id`          varchar(64)     NOT NULL DEFAULT '' COMMENT '兼容旧字段，等价于 owner_user_id',
  `create_time`      datetime        NOT NULL COMMENT '创建时间',
  `update_time`      datetime        NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_document_id` (`document_id`),
  KEY `idx_tenant_owner` (`tenant_id`, `owner_user_id`),
  KEY `idx_kb_scope` (`tenant_id`, `owner_user_id`, `knowledge_base_id`, `deleted_flag`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_deleted_flag` (`deleted_flag`),
  KEY `idx_status` (`processing_status`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_content_hash` (`tenant_id`, `owner_user_id`, `content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档元数据';

-- 老库增量迁移（CREATE TABLE IF NOT EXISTS 不会为已存在的表补列）：
-- ALTER TABLE `document_metadata`
--   ADD COLUMN `content_hash` char(64) NOT NULL DEFAULT '' COMMENT '内容SHA-256哈希(同用户去重)' AFTER `mime_type`,
--   ADD KEY `idx_content_hash` (`tenant_id`, `owner_user_id`, `content_hash`);

-- ============================================================
-- 图谱构建任务表 — 记录知识图谱构建任务状态
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `extraction_task` (
  `task_id`            varchar(64)     NOT NULL DEFAULT '' COMMENT '任务ID',
  `document_id`        varchar(64)     NOT NULL DEFAULT '' COMMENT '文档ID',
  `status`             varchar(32)     NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING | PROCESSING | COMPLETED | FAILED',
  `total_chunks`       int             NOT NULL DEFAULT 0 COMMENT '总分块数',
  `processed_chunks`   int             NOT NULL DEFAULT 0 COMMENT '已处理分块数',
  `extracted_entities` int             NOT NULL DEFAULT 0 COMMENT '抽取实体数',
  `extracted_relations` int            NOT NULL DEFAULT 0 COMMENT '抽取关系数',
  `error_message`      text            DEFAULT NULL COMMENT '错误信息',
  `created_at`         datetime        NOT NULL COMMENT '创建时间',
  `updated_at`         datetime        NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`task_id`),
  KEY `idx_document_id` (`document_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图谱构建任务';

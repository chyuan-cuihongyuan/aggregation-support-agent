CREATE TABLE IF NOT EXISTS `knowledge_base` (
  `id`                bigint unsigned NOT NULL AUTO_INCREMENT,
  `knowledge_base_id` varchar(64)      NOT NULL COMMENT '知识库ID',
  `tenant_id`         varchar(64)      NOT NULL DEFAULT '' COMMENT '租户ID',
  `owner_user_id`     varchar(64)      NOT NULL DEFAULT '' COMMENT '归属用户ID',
  `name`              varchar(128)     NOT NULL COMMENT '知识库名称',
  `description`       varchar(512)     NOT NULL DEFAULT '' COMMENT '知识库描述',
  `icon`              varchar(32)      NOT NULL DEFAULT '' COMMENT '图标',
  `color`             varchar(32)      NOT NULL DEFAULT '' COMMENT '颜色',
  `deleted_flag`      tinyint          NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除/1已删除',
  `create_time`       datetime         NOT NULL COMMENT '创建时间',
  `update_time`       datetime         NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_base_id` (`knowledge_base_id`),
  KEY `idx_tenant_owner` (`tenant_id`, `owner_user_id`, `deleted_flag`),
  KEY `idx_owner_name` (`owner_user_id`, `name`, `deleted_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库分类表';

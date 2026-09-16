CREATE TABLE IF NOT EXISTS `chat_session` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id`    varchar(128)    NOT NULL COMMENT '会话ID',
  `agent_id`      varchar(64)     NOT NULL DEFAULT '' COMMENT '智能体ID',
  `tenant_id`     varchar(64)     NOT NULL DEFAULT '' COMMENT '租户ID',
  `owner_user_id` varchar(64)     NOT NULL DEFAULT '' COMMENT '归属用户ID',
  `trace_id`      varchar(128)    NOT NULL DEFAULT '' COMMENT '创建会话时的traceId',
  `create_time`   datetime        NOT NULL COMMENT '创建时间',
  `update_time`   datetime        NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_id` (`session_id`),
  KEY `idx_tenant_owner_agent` (`tenant_id`, `owner_user_id`, `agent_id`),
  KEY `idx_owner_user_id` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话归属关系表';

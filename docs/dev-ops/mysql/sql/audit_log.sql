CREATE TABLE IF NOT EXISTS `audit_log` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id`       bigint          NOT NULL DEFAULT 0 COMMENT '操作者用户ID（未登录写 0）',
  `username`      varchar(64)     NOT NULL DEFAULT '' COMMENT '操作者用户名（冗余，便于审计阅读）',
  `action`        varchar(32)     NOT NULL COMMENT '动作类型：LOGIN/LOGOUT/REGISTER/UPLOAD_DOC/DELETE_DOC/CHANGE_ROLE/CHANGE_STATUS/RAG_QUERY',
  `resource_type` varchar(32)     NOT NULL DEFAULT '' COMMENT '资源类型：USER/DOCUMENT/SESSION 等',
  `resource_id`   varchar(128)    NOT NULL DEFAULT '' COMMENT '资源标识',
  `result`        varchar(16)     NOT NULL DEFAULT 'SUCCESS' COMMENT '结果：SUCCESS/FAILURE',
  `trace_id`      varchar(64)     NOT NULL DEFAULT '' COMMENT '请求追踪ID',
  `detail`        varchar(1024)   NOT NULL DEFAULT '' COMMENT '详情（错误信息或变更摘要）',
  `ip_address`    varchar(64)     NOT NULL DEFAULT '' COMMENT '客户端IP',
  `user_agent`    varchar(256)    NOT NULL DEFAULT '' COMMENT '客户端UA',
  `create_time`   datetime        NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_action` (`action`),
  KEY `idx_resource` (`resource_type`, `resource_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_trace_id` (`trace_id`),
  -- 按用户+动作+时间聚合/过滤：覆盖 statByUser 带 action 过滤、list 带 userId+action 组合查询
  KEY `idx_user_action_time` (`user_id`, `action`, `create_time`),
  -- 按动作+时间聚合/过滤：覆盖 statByAction 带时间范围、list 仅带 action 的高频场景
  KEY `idx_action_time` (`action`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志';

-- ============================================================
-- 对话历史表 — 记录每次用户提问和智能体回答
-- 对应数据库：chyuan_frame_archetype（或 xfg_frame_archetype）
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `chat_history` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id`   varchar(64)     NOT NULL DEFAULT '' COMMENT '租户ID',
  `owner_user_id` varchar(64)   NOT NULL DEFAULT '' COMMENT '归属用户ID',
  `user_id`     varchar(64)     NOT NULL DEFAULT '' COMMENT '兼容旧字段，等价于 owner_user_id',
  `agent_id`    varchar(64)     NOT NULL DEFAULT '' COMMENT '智能体ID',
  `agent_name`  varchar(128)    NOT NULL DEFAULT '' COMMENT '智能体名称',
  `session_id`  varchar(128)    NOT NULL DEFAULT '' COMMENT '会话ID',
  `question`    text            NOT NULL COMMENT '用户提问',
  `answer`      longtext        NOT NULL COMMENT '智能体回答（Markdown）',
  `trace_id`    varchar(128)    NOT NULL DEFAULT '' COMMENT '请求链路追踪ID',
  `prompt_tokens` int           NOT NULL DEFAULT 0 COMMENT '提示词Token数',
  `completion_tokens` int       NOT NULL DEFAULT 0 COMMENT '回答Token数',
  `create_time` datetime        NOT NULL COMMENT '创建时间',
  `update_time` datetime        NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_owner` (`tenant_id`, `owner_user_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_user_agent` (`user_id`, `agent_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话历史记录';

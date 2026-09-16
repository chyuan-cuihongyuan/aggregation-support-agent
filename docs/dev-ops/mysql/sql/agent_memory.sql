-- Agent Memory 数据库表结构
-- 数据库: chyuan_frame_archetype

-- 记忆主表
CREATE TABLE IF NOT EXISTS `agent_memory` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `memory_id` varchar(64) NOT NULL COMMENT '记忆唯一ID (UUID)',
  `tenant_id` varchar(64) NOT NULL COMMENT '租户ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `agent_id` varchar(64) NOT NULL COMMENT '智能体ID',
  `session_id` varchar(128) DEFAULT NULL COMMENT '会话ID (Session Memory可为空)',
  `content` text NOT NULL COMMENT '记忆内容',
  `content_hash` varchar(64) NOT NULL COMMENT '内容SHA-256哈希 (用于快速去重)',
  `memory_type` varchar(32) NOT NULL COMMENT '记忆类型: FACT/PREFERENCE/DECISION/EPISODE/KNOWLEDGE',
  `scope` varchar(255) NOT NULL DEFAULT '/' COMMENT '记忆作用域',
  `importance` float NOT NULL DEFAULT 0.5 COMMENT '重要性评分 0-1',
  `source` varchar(128) DEFAULT NULL COMMENT '来源标记',
  `metadata` json DEFAULT NULL COMMENT '扩展元数据',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态: 1-有效 0-已删除',
  `expires_at` datetime DEFAULT NULL COMMENT '过期时间 (NULL表示永不过期)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_id` (`memory_id`),
  KEY `idx_tenant_user` (`tenant_id`, `user_id`),
  KEY `idx_agent_session` (`agent_id`, `session_id`),
  KEY `idx_content_hash` (`content_hash`),
  UNIQUE KEY `uk_content_tenant_user` (`content_hash`, `tenant_id`, `user_id`),
  KEY `idx_scope` (`scope`(191)),
  KEY `idx_expires_at` (`expires_at`),
  KEY `idx_memory_type` (`memory_type`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent记忆表';

-- 记忆关联表 (用于Consolidation追踪)
CREATE TABLE IF NOT EXISTS `agent_memory_relation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `source_memory_id` varchar(64) NOT NULL COMMENT '源记忆ID',
  `target_memory_id` varchar(64) NOT NULL COMMENT '目标记忆ID',
  `relation_type` varchar(32) NOT NULL COMMENT '关系类型: DUPLICATE/SUPERSEDE/MERGE',
  `similarity_score` float NOT NULL COMMENT '相似度分数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_relation` (`source_memory_id`, `target_memory_id`),
  KEY `idx_target` (`target_memory_id`),
  KEY `idx_relation_type` (`relation_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='记忆关联关系表';

-- 记忆统计表 (可选，用于分析)
CREATE TABLE IF NOT EXISTS `agent_memory_stats` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) NOT NULL COMMENT '租户ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `agent_id` varchar(64) NOT NULL COMMENT '智能体ID',
  `total_memories` int NOT NULL DEFAULT 0 COMMENT '总记忆数',
  `active_memories` int NOT NULL DEFAULT 0 COMMENT '有效记忆数',
  `last_remember_at` datetime DEFAULT NULL COMMENT '最后存储时间',
  `last_recall_at` datetime DEFAULT NULL COMMENT '最后检索时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_tenant_user_agent` (`tenant_id`, `user_id`, `agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='记忆统计表';

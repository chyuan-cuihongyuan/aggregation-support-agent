-- ============================================================
-- chat_history 表结构迁移脚本
-- 日期：2024-05-24
-- 说明：添加缺失字段以匹配最新的代码定义
-- ============================================================

-- 1. 备份现有数据
CREATE TABLE IF NOT EXISTS chat_history_backup_20260524 AS SELECT * FROM chat_history;

-- 2. 添加 owner_user_id 字段（归属用户ID）
ALTER TABLE chat_history
ADD COLUMN owner_user_id varchar(64) NOT NULL DEFAULT '' COMMENT '归属用户ID' AFTER tenant_id;

-- 3. 添加 trace_id 字段（请求链路追踪ID）
ALTER TABLE chat_history
ADD COLUMN trace_id varchar(128) NOT NULL DEFAULT '' COMMENT '请求链路追踪ID' AFTER answer;

-- 4. 添加 prompt_tokens 字段（提示词Token数）
ALTER TABLE chat_history
ADD COLUMN prompt_tokens int NOT NULL DEFAULT 0 COMMENT '提示词Token数' AFTER trace_id;

-- 5. 添加 completion_tokens 字段（回答Token数）
ALTER TABLE chat_history
ADD COLUMN completion_tokens int NOT NULL DEFAULT 0 COMMENT '回答Token数' AFTER prompt_tokens;

-- 6. 添加复合索引（tenant_id + owner_user_id）
CREATE INDEX idx_tenant_owner ON chat_history (tenant_id, owner_user_id);

-- 7. 数据迁移：将 user_id 复制到 owner_user_id（用于历史数据兼容）
UPDATE chat_history SET owner_user_id = user_id WHERE owner_user_id = '';

-- 8. 验证表结构
SELECT
    COLUMN_NAME as '字段名',
    COLUMN_TYPE as '类型',
    IS_NULLABLE as '可空',
    COLUMN_DEFAULT as '默认值',
    COLUMN_COMMENT as '注释'
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'chat_history'
ORDER BY ORDINAL_POSITION;

-- 9. 验证索引
SHOW INDEX FROM chat_history;

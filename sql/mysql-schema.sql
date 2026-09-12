-- =============================================================================
-- AIOps 聚合支撑服务建表脚本（MySQL 版，共 10 表）
-- 工单 0123（三期 O3：PG 全量 DDL 翻译）补账：为从未入库过 DDL 的表补建脚本，2026-09-10。
-- 反推口径：alert / audit_log / chat_history / chat_session / document_metadata /
--   extraction_task / knowledge_base / rag_trace / user 九表取自
--   aggregation-support-agent-app 的 mybatis/mapper 全部 SQL 列集；
--   agent_memory 取自注解 mapper AgentMemoryMapper.java；字段类型按
--   aggregation-support-agent-infrastructure dao/po 对应 PO 反推。
-- 类型口径：TINYINT 对应 PO Integer 状态/开关列；INT 为计数类量值（PO Integer）；
--   BIGINT 对应 PO Long；Double 分数列 → DECIMAL(10,6)；metadata 类 JSON 列
--   （agent_memory.metadata PO 为 Map + JacksonTypeHandler，以 JSON 文本读写）
--   MySQL 侧用 JSON 类型；不使用 ON UPDATE CURRENT_TIMESTAMP，updated 时间统一由
--   应用层维护（mapper UPDATE 语句均已显式写 update_time/updated_at = NOW()）。
-- 唯一键反推依据（重点注明）：
--   alert             uk_alert_id   ：queryByAlertId / updateStatus / deleteByAlertId
--                                     均按 alert_id 单值定位（外部告警源唯一凭证）。
--   chat_session      uk_session_id ：queryBySessionId / querySessionByScope 按
--                                     session_id 定位单会话。
--   document_metadata uk_document_id：queryByDocumentId / updateStatus /
--                                     markDeletedByDocumentId 按单文档定位。
--   knowledge_base    uk_kb_id      ：queryByIdAndScope / markDeleted 按
--                                     knowledge_base_id 定位单库。
--   user              uk_username   ：queryByUsername 按用户名定位单用户（登录语义）。
--   agent_memory      uk_memory_id  ：selectActiveByMemoryId / softDelete /
--                                     updateContent 均按 memory_id 单值定位；
--                                     uk_content_tenant_user (content_hash, tenant_id,
--                                     user_id) 为工单给定内容去重唯一键，
--                                     countByContentHash 按同三列组合判重印证。
--   audit_log / chat_history / rag_trace / extraction_task：extraction_task 的
--                                     task_id 为业务主键（PO 无自增 id，resultMap
--                                     id 列即 task_id）；其余三表纯追加账本无唯一键。
-- 特别说明：knowledge_base 的 document_count 为 queryByScope 中 COUNT(dm.id) 的
--   聚合别名，非物理列，故不建该列。
-- =============================================================================

-- 1. 告警表（alert_mapper.xml + AlertPO）
CREATE TABLE IF NOT EXISTS alert (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  alert_id     VARCHAR(64)  NOT NULL COMMENT '告警业务ID（外部告警源唯一凭证）',
  severity     VARCHAR(16)  NOT NULL COMMENT '严重级别：critical/warning/info',
  name         VARCHAR(128) NOT NULL COMMENT '告警名称',
  summary      VARCHAR(512) NULL COMMENT '告警摘要',
  host         VARCHAR(64)  NULL COMMENT '告警主机',
  status       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT '状态：active/resolved 等',
  source       VARCHAR(32)  NULL COMMENT '告警来源',
  metrics_json JSON         NULL COMMENT '指标快照（JSON 文本，PO String）',
  labels_json  JSON         NULL COMMENT '标签集（JSON 文本，PO String）',
  description  TEXT         NULL COMMENT '告警描述',
  alert_time   DATETIME     NOT NULL COMMENT '告警发生时间',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_alert_id (alert_id),
  KEY idx_alert_status_time (status, alert_time)
) COMMENT 'AIOps告警表';

-- 2. 审计日志表（audit_log_mapper.xml + AuditLogPO，只增不改）
CREATE TABLE IF NOT EXISTS audit_log (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  user_id       BIGINT       NULL COMMENT '操作用户ID（关联 user.id，PO Long）',
  username      VARCHAR(64)  NOT NULL COMMENT '操作用户名（冗余快照）',
  action        VARCHAR(64)  NOT NULL COMMENT '操作类型',
  resource_type VARCHAR(32)  NULL COMMENT '资源类型',
  resource_id   VARCHAR(64)  NULL COMMENT '资源ID',
  result        VARCHAR(16)  NOT NULL COMMENT '结果：SUCCESS/FAILURE',
  trace_id      VARCHAR(64)  NULL COMMENT '链路追踪ID',
  detail        TEXT         NULL COMMENT '操作明细（JSON 文本）',
  ip_address    VARCHAR(64)  NULL COMMENT '来源IP',
  user_agent    VARCHAR(512) NULL COMMENT 'User-Agent',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_audit_user_time (user_id, create_time),
  KEY idx_audit_create (create_time)
) COMMENT '审计日志表';

-- 3. 对话历史表（chat_history_mapper.xml + ChatHistoryPO）
CREATE TABLE IF NOT EXISTS chat_history (
  id                BIGINT      AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  tenant_id         VARCHAR(64) NOT NULL DEFAULT '' COMMENT '租户ID',
  owner_user_id     VARCHAR(64) NOT NULL DEFAULT '' COMMENT '归属用户ID',
  user_id           VARCHAR(64) NULL COMMENT '消息产生时的用户标识（可为空）',
  agent_id          VARCHAR(64) NULL COMMENT '智能体ID',
  agent_name        VARCHAR(128) NULL COMMENT '智能体名称快照',
  session_id        VARCHAR(64) NULL COMMENT '会话ID',
  question          TEXT        COMMENT '用户问题',
  answer            MEDIUMTEXT  COMMENT '模型回答（长文本）',
  trace_id          VARCHAR(64) NULL COMMENT '链路追踪ID',
  prompt_tokens     INT         NULL COMMENT 'Prompt Token（PO Integer）',
  completion_tokens INT         NULL COMMENT 'Completion Token（PO Integer）',
  create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  KEY idx_history_scope_time (tenant_id, owner_user_id, create_time),
  KEY idx_history_session (session_id, create_time)
) COMMENT '对话历史表';

-- 4. 对话会话表（chat_session_mapper.xml / chat_history_mapper.xml insertSession + ChatSessionPO）
CREATE TABLE IF NOT EXISTS chat_session (
  id            BIGINT      AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  session_id    VARCHAR(64) NOT NULL COMMENT '会话业务ID（唯一）',
  agent_id      VARCHAR(64) NULL COMMENT '智能体ID',
  tenant_id     VARCHAR(64) NOT NULL DEFAULT '' COMMENT '租户ID',
  owner_user_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '归属用户ID',
  trace_id      VARCHAR(64) NULL COMMENT '链路追踪ID',
  create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_session_id (session_id)
) COMMENT '对话会话表';

-- 5. 文档元数据表（document_metadata_mapper.xml + DocumentMetadataPO）
CREATE TABLE IF NOT EXISTS document_metadata (
  id                BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  document_id       VARCHAR(64)  NOT NULL COMMENT '文档业务ID（唯一）',
  tenant_id         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户ID',
  owner_user_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '归属用户ID',
  knowledge_base_id VARCHAR(64)  NULL COMMENT '所属知识库业务ID',
  knowledge_base_name VARCHAR(128) NULL COMMENT '知识库名称快照',
  file_name         VARCHAR(256) NOT NULL COMMENT '文件名',
  file_extension    VARCHAR(16)  NULL COMMENT '文件扩展名',
  file_size         BIGINT       NULL COMMENT '文件大小（字节，PO Long）',
  mime_type         VARCHAR(128) NULL COMMENT 'MIME 类型',
  total_chars       INT          NULL COMMENT '总字符数（PO Integer）',
  total_chunks      INT          NULL COMMENT '总分块数（PO Integer）',
  section_count     INT          NULL COMMENT '章节计数（PO Integer）',
  processing_status VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '处理状态：PENDING/PROCESSING/COMPLETED/FAILED',
  error_message     TEXT         NULL COMMENT '错误信息',
  visibility        VARCHAR(16)  NULL COMMENT '可见性：PRIVATE/SHARED 等',
  deleted_flag      TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记：0-正常，1-已删除（PO Integer）',
  user_id           VARCHAR(64)  NULL COMMENT '上传用户标识',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_document_id (document_id),
  KEY idx_doc_scope (tenant_id, owner_user_id, deleted_flag)
) COMMENT '文档元数据表';

-- 6. 图谱抽取任务表（extraction_task_mapper.xml + ExtractionTaskPO；
--    PO 无自增 id，task_id 即业务主键）
CREATE TABLE IF NOT EXISTS extraction_task (
  task_id            VARCHAR(64) NOT NULL COMMENT '任务业务ID（主键）',
  document_id        VARCHAR(64) NOT NULL COMMENT '文档业务ID',
  status             VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING/PROCESSING/COMPLETED/FAILED',
  total_chunks       INT         NOT NULL DEFAULT 0 COMMENT '总分块数（PO Integer）',
  processed_chunks   INT         NOT NULL DEFAULT 0 COMMENT '已处理分块数（PO Integer）',
  extracted_entities INT         NOT NULL DEFAULT 0 COMMENT '抽取实体数（PO Integer）',
  extracted_relations INT        NOT NULL DEFAULT 0 COMMENT '抽取关系数（PO Integer）',
  error_message      TEXT        NULL COMMENT '错误信息',
  created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  PRIMARY KEY (task_id),
  KEY idx_extraction_doc (document_id)
) COMMENT '图谱抽取任务表';

-- 7. 知识库表（knowledge_base_mapper.xml + KnowledgeBasePO；
--    document_count 为查询期 COUNT 聚合别名，非物理列）
CREATE TABLE IF NOT EXISTS knowledge_base (
  id                BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  knowledge_base_id VARCHAR(64)  NOT NULL COMMENT '知识库业务ID（唯一）',
  tenant_id         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户ID',
  owner_user_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '归属用户ID',
  name              VARCHAR(128) NOT NULL COMMENT '知识库名称',
  description       VARCHAR(512) NULL COMMENT '知识库描述',
  icon              VARCHAR(64)  NULL COMMENT '图标标识',
  color             VARCHAR(16)  NULL COMMENT '主题色',
  deleted_flag      TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记：0-正常，1-已删除（PO Integer）',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_kb_id (knowledge_base_id),
  KEY idx_kb_scope (tenant_id, owner_user_id, deleted_flag)
) COMMENT '知识库表';

-- 8. RAG 检索追踪表（rag_trace_mapper.xml + RagTracePO；
--    trace_id 按注释为「请求维度唯一」，但查询带租户作用域 + LIMIT 1，
--    保守不建唯一键，仅普通索引，见文件头说明）
CREATE TABLE IF NOT EXISTS rag_trace (
  id                  BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  trace_id            VARCHAR(64)   NOT NULL COMMENT '追踪ID（请求维度唯一，未建唯一键：查询带作用域+LIMIT 1）',
  tenant_id           VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '租户ID',
  owner_user_id       VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '归属用户ID',
  session_id          VARCHAR(64)   NULL COMMENT '会话ID（AIOps 工具触发时可为空）',
  agent_id            VARCHAR(64)   NULL COMMENT '智能体ID',
  query_text          TEXT          COMMENT '原始查询文本',
  rewrite_text        TEXT          COMMENT 'Query 改写后的检索文本',
  retrieval_topk      INT           NULL COMMENT '检索 TopK（PO Integer）',
  source_docs         TEXT          COMMENT '命中证据 JSON 字符串（PO String）',
  parent_ids          TEXT          NULL COMMENT '命中子块对应父块ID列表（JSON 数组文本，工单 0166 父子分块；存量行 NULL）',
  parent_texts        TEXT          NULL COMMENT '命中子块对应父块文本列表（JSON 数组文本，工单 0166 父子分块；存量行 NULL）',
  answer_score        DECIMAL(10,6) NULL COMMENT '答案质量分（预留，PO Double）',
  hallucination_score DECIMAL(10,6) NULL COMMENT '幻觉率（预留，PO Double）',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_rag_trace_trace (trace_id),
  KEY idx_rag_trace_scope (tenant_id, owner_user_id, create_time),
  KEY idx_rag_trace_session (session_id, create_time)
) COMMENT 'RAG检索追踪表';

-- 9. 用户表（user_mapper.xml + UserPO；MySQL 表名 user 非保留字可直接使用）
CREATE TABLE IF NOT EXISTS user (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  username    VARCHAR(64)  NOT NULL COMMENT '用户名（唯一，登录凭证）',
  password    VARCHAR(100) NOT NULL COMMENT '密码哈希（BCrypt）',
  phone       VARCHAR(20)  NULL COMMENT '手机号',
  email       VARCHAR(128) NULL COMMENT '邮箱',
  nickname    VARCHAR(64)  NULL COMMENT '昵称',
  avatar      VARCHAR(512) NULL COMMENT '头像地址',
  role        VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN/USER 等',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用（PO Integer）',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_username (username)
) COMMENT '用户表';

-- 10. Agent 记忆表（注解 mapper AgentMemoryMapper.java + AgentMemoryPO；
--     metadata 为 PO Map + JacksonTypeHandler 以 JSON 文本读写 → MySQL JSON 类型）
CREATE TABLE IF NOT EXISTS agent_memory (
  id           BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID（IdType.AUTO）',
  memory_id    VARCHAR(64)   NOT NULL COMMENT '记忆业务ID（唯一，定位/软删/更新依据）',
  tenant_id    VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '租户ID',
  user_id      VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '用户ID',
  agent_id     VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '智能体ID',
  session_id   VARCHAR(64)   NULL COMMENT '会话ID（可为空）',
  content      TEXT          NOT NULL COMMENT '记忆内容',
  content_hash VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '内容哈希（去重键成员）',
  memory_type  VARCHAR(32)   NOT NULL DEFAULT 'FACT' COMMENT '记忆类型：FACT 等',
  scope        VARCHAR(256)  NOT NULL DEFAULT '/' COMMENT '作用域路径（前缀匹配，如 /agent/session）',
  importance   DECIMAL(3,2)  NOT NULL DEFAULT 0.50 COMMENT '重要度 0-1（PO Float）',
  source       VARCHAR(32)   NULL COMMENT '记忆来源',
  metadata     JSON          NULL COMMENT '扩展元数据（PO Map + JacksonTypeHandler，JSON 文本读写）',
  status       TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0-已删除（软删），1-有效（PO Integer）',
  expires_at   DATETIME      NULL COMMENT '过期时间，NULL 永不过期',
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_memory_id (memory_id),
  UNIQUE KEY uk_content_tenant_user (content_hash, tenant_id, user_id),
  KEY idx_memory_tenant_user (tenant_id, user_id, status),
  KEY idx_memory_scope (scope),
  KEY idx_memory_expire (tenant_id, user_id, expires_at)
) COMMENT 'Agent记忆表';

-- =============================================================================
-- 存量库迁移段（工单 0166 父子分块，检索日志 rag_trace 增列；新库由上方
-- CREATE TABLE 定义直接生效。MySQL 不支持 ADD COLUMN IF NOT EXISTS，
-- 存量库按需手工执行并先验证列不存在）：
-- ALTER TABLE rag_trace ADD COLUMN parent_ids   TEXT NULL COMMENT '命中子块对应父块ID列表（JSON 数组文本，工单 0166 父子分块）';
-- ALTER TABLE rag_trace ADD COLUMN parent_texts TEXT NULL COMMENT '命中子块对应父块文本列表（JSON 数组文本，工单 0166 父子分块）';
-- =============================================================================

-- 11. 租户知识库配额表（tenant_knowledge_quota_mapper.xml + TenantKnowledgeQuotaPO，
--     工单 0168：文档/分块入库前配额校验；表中无对应租户行 = 不限制（存量兼容），
--     行内 max_documents / max_chunks 为 NULL 同样视为不限制；update_time 应用层维护）
CREATE TABLE IF NOT EXISTS tenant_knowledge_quota (
  id            BIGINT      AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  tenant_id     VARCHAR(64) NOT NULL COMMENT '租户ID（唯一定位，未配置租户=不限制）',
  max_documents INT         NULL COMMENT '文档数上限（NULL=不限制，PO Integer）',
  max_chunks    INT         NULL COMMENT '分块数上限（NULL=不限制，PO Integer）',
  create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_quota_tenant (tenant_id)
) COMMENT '租户知识库配额表';

-- 14. 工作流运行表（工单 0212 AB9）
CREATE TABLE IF NOT EXISTS workflow_run (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id           VARCHAR(64)  NOT NULL COMMENT '运行 id（UUID）',
  workflow_name    VARCHAR(128) NOT NULL COMMENT '工作流名',
  workflow_version INT          NOT NULL DEFAULT 1 COMMENT '版本号',
  tenant_id        VARCHAR(64)  NULL COMMENT '租户（灰度切流来源）',
  status           VARCHAR(16)  NOT NULL COMMENT 'COMPLETED/FAILED/INTERRUPTED',
  failed_node_id   VARCHAR(128) NULL COMMENT '失败节点',
  error            VARCHAR(512) NULL COMMENT '错误摘要',
  duration_ms      BIGINT       NULL COMMENT '总耗时毫秒',
  node_runs_json   TEXT         NULL COMMENT '节点级明细 JSON 数组',
  create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_workflow_run_id (run_id),
  KEY idx_workflow_run_name (workflow_name, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流运行历史（工单 0212 AB9）';

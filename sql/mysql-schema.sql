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

-- 15. 检索参数画像表（工单 0235 AE8）
CREATE TABLE IF NOT EXISTS retrieval_profile (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_key   VARCHAR(128) NOT NULL COMMENT '配置组合键（topK/ef/vr）',
  samples       INT          NOT NULL DEFAULT 0 COMMENT '样本数',
  avg_hit_rate  DOUBLE       NOT NULL DEFAULT 0 COMMENT '平均命中率',
  avg_latency_ms DOUBLE      NOT NULL DEFAULT 0 COMMENT '平均延迟毫秒',
  p95_latency_ms BIGINT      NOT NULL DEFAULT 0 COMMENT 'P95 延迟毫秒',
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_retrieval_profile_key (profile_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检索参数画像（工单 0235 AE8）';

-- 16. 工作流蓝图模板表（工单 0268 AI1）
CREATE TABLE IF NOT EXISTS workflow_blueprint (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  name             VARCHAR(128) NOT NULL COMMENT '蓝图名',
  description      VARCHAR(512) NULL COMMENT '描述',
  category         VARCHAR(64)  NOT NULL DEFAULT 'general' COMMENT '分类',
  tags             VARCHAR(256) NULL COMMENT '标签（逗号拼接）',
  graph_json       TEXT         NOT NULL COMMENT '图定义 DSL JSON（可含 ${param} 占位）',
  param_schema_json TEXT        NULL COMMENT '参数 schema JSON',
  operator         VARCHAR(64)  NOT NULL DEFAULT 'unknown' COMMENT '操作人',
  create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_workflow_blueprint_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流蓝图模板（工单 0268 AI1）';

-- 17. 图谱索引表（工单 0308 AM3：AM1 图索引构建快照落档）
CREATE TABLE IF NOT EXISTS graph_index (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  index_id      VARCHAR(64)  NOT NULL COMMENT '索引ID',
  document_id   VARCHAR(128) NOT NULL COMMENT '来源文档ID',
  unit_count    INT          NOT NULL DEFAULT 0 COMMENT '文本块数',
  node_count    INT          NOT NULL DEFAULT 0 COMMENT '节点数',
  edge_count    INT          NOT NULL DEFAULT 0 COMMENT '边数',
  index_hash    VARCHAR(64)  NOT NULL COMMENT '规范化序列化 SHA-256（重放校验）',
  graph_json    TEXT         NULL COMMENT '索引快照 JSON',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_graph_index_id (index_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图谱索引快照（工单 0308 AM3）';

-- 18. 图谱社区表（工单 0308 AM3：社区划分 + C0/C1/C2 分层摘要）
CREATE TABLE IF NOT EXISTS graph_community (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  index_id      VARCHAR(64)  NOT NULL COMMENT '索引ID',
  community_id  VARCHAR(64)  NOT NULL COMMENT '社区ID（c_ + 胜出标签）',
  level         INT          NOT NULL DEFAULT 0 COMMENT '层级：0=C0 基础，1=C1 聚合，2=C2 顶层',
  summary_text  TEXT         NULL COMMENT '社区摘要文本',
  member_keys   TEXT         NULL COMMENT '成员（叶子=节点键，聚合=子社区ID，逗号拼接）',
  member_count  INT          NOT NULL DEFAULT 0 COMMENT '成员数',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_graph_community (index_id, community_id, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图谱社区与分层摘要（工单 0308 AM3）';

-- 19. 浏览器任务模板表（工单 0344 AQ6：目标+参数占位符+动作序列模板）
CREATE TABLE IF NOT EXISTS browser_task_template (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(128) NOT NULL COMMENT '模板名（唯一）',
  goal          VARCHAR(512) NULL COMMENT '目标描述',
  parameters    TEXT         NULL COMMENT '参数占位符 JSON',
  actions_json  TEXT         NOT NULL COMMENT '动作序列模板 JSON',
  tenant_id     VARCHAR(64)  NULL COMMENT '租户',
  operator      VARCHAR(64)  NOT NULL DEFAULT 'unknown' COMMENT '操作人',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_browser_template_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='浏览器任务模板（工单 0344 AQ6）';

-- 20. 研究任务表（工单 0352/0353 AR6-AR7：状态机 + 检查点快照）
CREATE TABLE IF NOT EXISTS research_task (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id        VARCHAR(64)  NOT NULL COMMENT '任务ID（唯一）',
  topic          VARCHAR(256) NOT NULL COMMENT '研究主题',
  perspectives   VARCHAR(256) NULL COMMENT '视角清单（逗号拼接）',
  status         VARCHAR(16)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PLANNING/SEARCHING/DRAFTING/CITING/DONE/FAILED/CANCELLED',
  checkpoint_json TEXT        NULL COMMENT '检查点快照 JSON',
  token_cost     BIGINT       NOT NULL DEFAULT 0 COMMENT 'token 成本',
  duration_ms    BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时毫秒',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_research_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研究任务（工单 0352 AR6）';

-- 21. 研究报告表（工单 0353 AR7：Markdown + 引用表 + 元数据）
CREATE TABLE IF NOT EXISTS research_report (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id        VARCHAR(64)  NOT NULL COMMENT '任务ID（唯一）',
  topic          VARCHAR(256) NOT NULL COMMENT '主题',
  markdown       TEXT         NOT NULL COMMENT '报告 Markdown',
  citations_json TEXT         NULL COMMENT '引用表 JSON',
  metadata_json  TEXT         NULL COMMENT '元数据 JSON',
  citation_rate  DOUBLE       NOT NULL DEFAULT 0 COMMENT '引用对齐率',
  partial        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否部分报告 0/1',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_research_report_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研究报告（工单 0353 AR7）';

-- 22. 时序记忆事实边表（工单 0362 AS1：bi-temporal 双时间线）
CREATE TABLE IF NOT EXISTS tmemory_edge (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  edge_id        VARCHAR(64)  NOT NULL COMMENT '边标识（唯一）',
  subject        VARCHAR(256) NOT NULL COMMENT '主语实体名',
  predicate      VARCHAR(128) NOT NULL COMMENT '谓语（关系）',
  object_entity  VARCHAR(256) NOT NULL COMMENT '宾语实体名',
  valid_from     BIGINT       NOT NULL COMMENT '事实生效时间 epoch ms',
  valid_to       BIGINT       NULL COMMENT '事实失效时间（null 仍有效）',
  invalid_reason VARCHAR(32)  NULL COMMENT '失效原因 SUPERSEDED/CONFLICT',
  ingest_seq     BIGINT       NOT NULL COMMENT '事务时间入库序号',
  confidence     DOUBLE       NOT NULL DEFAULT 0.5 COMMENT '置信度 0-1',
  source         VARCHAR(64)  NOT NULL DEFAULT 'unknown' COMMENT '来源标识',
  kind           VARCHAR(16)  NOT NULL DEFAULT 'EPISODIC' COMMENT 'EPISODIC/SEMANTIC',
  access_count   INT          NOT NULL DEFAULT 0 COMMENT '访问计数',
  score          DOUBLE       NOT NULL DEFAULT 0.5 COMMENT '价值分数',
  promoted_from  VARCHAR(512) NULL COMMENT '晋升来源批次（逗号拼接）',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tmemory_edge_id (edge_id),
  KEY idx_tmemory_edge_subject (subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='时序记忆事实边（工单 0362 AS1 bi-temporal）';

-- 23. 语音转写任务表（工单 0386 AU8：ASR 端口产物持久化）
CREATE TABLE IF NOT EXISTS speech_transcript (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  transcript_id  VARCHAR(64)  NOT NULL COMMENT '转写ID（唯一）',
  audio_ref      VARCHAR(512) NOT NULL COMMENT '音频引用',
  language       VARCHAR(16)  NOT NULL DEFAULT 'zh' COMMENT '语言',
  segments_json  TEXT         NULL COMMENT '段序列 JSON（起止/文本/说话人/词）',
  metrics_json   TEXT         NULL COMMENT '质量指标 JSON（WER/CER）',
  duration_ms    BIGINT       NOT NULL DEFAULT 0 COMMENT '音频时长毫秒',
  status         VARCHAR(16)  NOT NULL DEFAULT 'DONE' COMMENT 'RUNNING/DONE/FAILED',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_speech_transcript_id (transcript_id),
  KEY idx_speech_transcript_audio (audio_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语音转写任务（工单 0386 AU8）';

-- 24. 文档解析任务表（工单 0395 AV9：版面结构 + 质量记分持久化）
CREATE TABLE IF NOT EXISTS doc_parse_task (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id        VARCHAR(64)  NOT NULL COMMENT '任务ID（唯一）',
  doc_ref        VARCHAR(512) NOT NULL COMMENT '文档引用',
  page           INT          NOT NULL DEFAULT 1 COMMENT '页码',
  status         VARCHAR(16)  NOT NULL DEFAULT 'DONE' COMMENT 'RUNNING/DONE/FAILED',
  layout_json    TEXT         NULL COMMENT '版面元素 JSON',
  score_json     TEXT         NULL COMMENT '版面质量记分 JSON',
  parse_ms       BIGINT       NOT NULL DEFAULT 0 COMMENT '解析耗时毫秒',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_doc_parse_task_id (task_id),
  KEY idx_doc_parse_task_doc (doc_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档解析任务（工单 0395 AV9）';

-- 25. 搜索索引文档表（工单 0404 AW9：searchkernel 进程内倒排的持久化面）
CREATE TABLE IF NOT EXISTS search_index_doc (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id         VARCHAR(128) NOT NULL COMMENT '文档ID（唯一）',
  index_name     VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '索引名',
  title          VARCHAR(256) NULL COMMENT '标题',
  body           TEXT         NULL COMMENT '正文',
  fields_json    TEXT         NULL COMMENT '过滤面字段 JSON',
  version        BIGINT       NOT NULL DEFAULT 1 COMMENT '版本（严格递增）',
  deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '墓碑 0/1',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_search_doc_id (doc_id),
  KEY idx_search_doc_index (index_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='搜索索引文档（工单 0404 AW9）';

-- 26. 爬取清单表（工单 0425 AY7：crawler 域 URL 状态机）
CREATE TABLE IF NOT EXISTS crawl_url (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  fingerprint    VARCHAR(64)  NOT NULL COMMENT 'URL 归一化指纹（SHA-256）',
  raw_url        VARCHAR(768) NOT NULL COMMENT '原始 URL',
  domain         VARCHAR(128) NOT NULL DEFAULT '' COMMENT '域名（节流分组）',
  depth          INT          NOT NULL DEFAULT 0 COMMENT '深度',
  priority       INT          NOT NULL DEFAULT 0 COMMENT '域内优先级',
  status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/FETCHED/FAILED',
  retry_count    INT          NOT NULL DEFAULT 0 COMMENT '重试计数',
  content_hash   VARCHAR(64)  NULL COMMENT '内容指纹',
  fetched_at     BIGINT       NULL COMMENT '最后抓取时间 epoch ms',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_crawl_url_fp (fingerprint),
  KEY idx_crawl_url_status (status, depth)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='爬取清单（工单 0425 AY7）';

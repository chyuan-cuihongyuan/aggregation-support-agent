-- =============================================================================
-- AIOps 聚合支撑服务建表脚本（PostgreSQL 版，共 10 表）
-- 工单 0123（三期 O3：PG 全量 DDL 翻译）补账：为从未入库过 DDL 的表补建脚本，2026-09-10；
-- 列集与同目录 mysql-schema.sql（MySQL 版）一一对应。
-- 反推口径：alert / audit_log / chat_history / chat_session / document_metadata /
--   extraction_task / knowledge_base / rag_trace / user 九表取自
--   aggregation-support-agent-app 的 mybatis/mapper 全部 SQL 列集；
--   agent_memory 取自注解 mapper AgentMemoryMapper.java；字段类型按
--   aggregation-support-agent-infrastructure dao/po 对应 PO 反推。
-- 类型映射（对齐 dev-ops/postgresql/01-gateway-seed.sql 口径）：
--   AUTO_INCREMENT → BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY；
--   DATETIME → TIMESTAMP；ON UPDATE CURRENT_TIMESTAMP 移除（双方言统一改应用层
--   维护，mapper UPDATE 语句均已显式写 update_time/updated_at = NOW()）；
--   TINYINT（PO Integer 状态/开关）→ SMALLINT（保持 JDBC Integer 映射）；
--   MEDIUMTEXT → TEXT；DECIMAL 双方言同名。
-- JSON 列取舍（重要）：alert.metrics_json / alert.labels_json（PO String）与
--   agent_memory.metadata（PO Map + JacksonTypeHandler）均以 JSON 文本经
--   MyBatis 字符串通道读写。PG 侧统一用 TEXT 而非 jsonb/json：jsonb 会改变
--   出参形态（键序/空白/类型标注）并要求入参强校验，TEXT 可原样往返 PO 的
--   字符串语义，保证 MyBatis 双轨兼容（工单 0123 指示口径）。
-- 保留字处理：user 在 PG 为保留字，建表使用 "user"（带双引号）；应用层
--   mapper 中裸写的 FROM user 在 PG 方言下需适配为 "user"（属三期应用层
--   双轨改造范围，本 DDL 先行落位）。
-- 唯一键反推依据（重点注明）：
--   alert             uk_alert_id   ：queryByAlertId / updateStatus / deleteByAlertId
--                                     均按 alert_id 单值定位（外部告警源唯一凭证）。
--   chat_session      uk_session_id ：queryBySessionId / querySessionByScope 按
--                                     session_id 定位单会话。
--   document_metadata uk_document_id：queryByDocumentId / updateStatus /
--                                     markDeletedByDocumentId 按单文档定位。
--   knowledge_base    uk_kb_id      ：queryByIdAndScope / markDeleted 按
--                                     knowledge_base_id 定位单库。
--   "user"            uk_username   ：queryByUsername 按用户名定位单用户（登录语义）。
--   agent_memory      uk_memory_id  ：selectActiveByMemoryId / softDelete /
--                                     updateContent 均按 memory_id 单值定位；
--                                     uk_content_tenant_user (content_hash, tenant_id,
--                                     user_id) 为工单给定内容去重唯一键，
--                                     countByContentHash 按同三列组合判重印证。
--   extraction_task：task_id 为业务主键（PO 无自增 id，resultMap id 列即 task_id）；
--   audit_log / chat_history / rag_trace：纯追加账本，无唯一键。
-- 特别说明：knowledge_base 的 document_count 为 queryByScope 中 COUNT(dm.id) 的
--   聚合别名，非物理列，故不建该列；rag_trace.trace_id 按注释为「请求维度唯一」，
--   但 selectByTraceId 查询带租户作用域 + LIMIT 1（防御写法），保守不建唯一键。
-- =============================================================================

-- 1. 告警表（alert_mapper.xml + AlertPO）
CREATE TABLE IF NOT EXISTS alert (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alert_id     VARCHAR(64)  NOT NULL,
  severity     VARCHAR(16)  NOT NULL,
  name         VARCHAR(128) NOT NULL,
  summary      VARCHAR(512) NULL,
  host         VARCHAR(64)  NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'active',
  source       VARCHAR(32)  NULL,
  metrics_json TEXT         NULL,
  labels_json  TEXT         NULL,
  description  TEXT         NULL,
  alert_time   TIMESTAMP    NOT NULL,
  create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_alert_id UNIQUE (alert_id)
);
COMMENT ON TABLE alert IS 'AIOps告警表';
COMMENT ON COLUMN alert.alert_id IS '告警业务ID（外部告警源唯一凭证）';
COMMENT ON COLUMN alert.severity IS '严重级别：critical/warning/info';
COMMENT ON COLUMN alert.name IS '告警名称';
COMMENT ON COLUMN alert.summary IS '告警摘要';
COMMENT ON COLUMN alert.host IS '告警主机';
COMMENT ON COLUMN alert.status IS '状态：active/resolved 等';
COMMENT ON COLUMN alert.source IS '告警来源';
COMMENT ON COLUMN alert.metrics_json IS '指标快照（JSON 文本，PO String→TEXT）';
COMMENT ON COLUMN alert.labels_json IS '标签集（JSON 文本，PO String→TEXT）';
COMMENT ON COLUMN alert.description IS '告警描述';
COMMENT ON COLUMN alert.alert_time IS '告警发生时间';
COMMENT ON COLUMN alert.create_time IS '创建时间';
COMMENT ON COLUMN alert.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_alert_status_time ON alert (status, alert_time);

-- 2. 审计日志表（audit_log_mapper.xml + AuditLogPO，只增不改）
CREATE TABLE IF NOT EXISTS audit_log (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id       BIGINT       NULL,
  username      VARCHAR(64)  NOT NULL,
  action        VARCHAR(64)  NOT NULL,
  resource_type VARCHAR(32)  NULL,
  resource_id   VARCHAR(64)  NULL,
  result        VARCHAR(16)  NOT NULL,
  trace_id      VARCHAR(64)  NULL,
  detail        TEXT         NULL,
  ip_address    VARCHAR(64)  NULL,
  user_agent    VARCHAR(512) NULL,
  create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE audit_log IS '审计日志表';
COMMENT ON COLUMN audit_log.user_id IS '操作用户ID（关联 "user".id，PO Long→BIGINT）';
COMMENT ON COLUMN audit_log.username IS '操作用户名（冗余快照）';
COMMENT ON COLUMN audit_log.action IS '操作类型';
COMMENT ON COLUMN audit_log.resource_type IS '资源类型';
COMMENT ON COLUMN audit_log.resource_id IS '资源ID';
COMMENT ON COLUMN audit_log.result IS '结果：SUCCESS/FAILURE';
COMMENT ON COLUMN audit_log.trace_id IS '链路追踪ID';
COMMENT ON COLUMN audit_log.detail IS '操作明细（JSON 文本）';
COMMENT ON COLUMN audit_log.ip_address IS '来源IP';
COMMENT ON COLUMN audit_log.user_agent IS 'User-Agent';
COMMENT ON COLUMN audit_log.create_time IS '创建时间';
CREATE INDEX IF NOT EXISTS idx_audit_user_time ON audit_log (user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_audit_create ON audit_log (create_time);

-- 3. 对话历史表（chat_history_mapper.xml + ChatHistoryPO）
CREATE TABLE IF NOT EXISTS chat_history (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id         VARCHAR(64)  NOT NULL DEFAULT '',
  owner_user_id     VARCHAR(64)  NOT NULL DEFAULT '',
  user_id           VARCHAR(64)  NULL,
  agent_id          VARCHAR(64)  NULL,
  agent_name        VARCHAR(128) NULL,
  session_id        VARCHAR(64)  NULL,
  question          TEXT,
  answer            TEXT,
  trace_id          VARCHAR(64)  NULL,
  prompt_tokens     INT          NULL,
  completion_tokens INT          NULL,
  create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE chat_history IS '对话历史表';
COMMENT ON COLUMN chat_history.tenant_id IS '租户ID';
COMMENT ON COLUMN chat_history.owner_user_id IS '归属用户ID';
COMMENT ON COLUMN chat_history.user_id IS '消息产生时的用户标识（可为空）';
COMMENT ON COLUMN chat_history.agent_id IS '智能体ID';
COMMENT ON COLUMN chat_history.agent_name IS '智能体名称快照';
COMMENT ON COLUMN chat_history.session_id IS '会话ID';
COMMENT ON COLUMN chat_history.question IS '用户问题';
COMMENT ON COLUMN chat_history.answer IS '模型回答（长文本，MySQL MEDIUMTEXT→TEXT）';
COMMENT ON COLUMN chat_history.trace_id IS '链路追踪ID';
COMMENT ON COLUMN chat_history.prompt_tokens IS 'Prompt Token（PO Integer→INT）';
COMMENT ON COLUMN chat_history.completion_tokens IS 'Completion Token（PO Integer→INT）';
COMMENT ON COLUMN chat_history.create_time IS '创建时间';
COMMENT ON COLUMN chat_history.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_history_scope_time ON chat_history (tenant_id, owner_user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_history_session ON chat_history (session_id, create_time);

-- 4. 对话会话表（chat_session_mapper.xml / chat_history_mapper.xml insertSession + ChatSessionPO）
CREATE TABLE IF NOT EXISTS chat_session (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  session_id    VARCHAR(64) NOT NULL,
  agent_id      VARCHAR(64) NULL,
  tenant_id     VARCHAR(64) NOT NULL DEFAULT '',
  owner_user_id VARCHAR(64) NOT NULL DEFAULT '',
  trace_id      VARCHAR(64) NULL,
  create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_session_id UNIQUE (session_id)
);
COMMENT ON TABLE chat_session IS '对话会话表';
COMMENT ON COLUMN chat_session.session_id IS '会话业务ID（唯一）';
COMMENT ON COLUMN chat_session.agent_id IS '智能体ID';
COMMENT ON COLUMN chat_session.tenant_id IS '租户ID';
COMMENT ON COLUMN chat_session.owner_user_id IS '归属用户ID';
COMMENT ON COLUMN chat_session.trace_id IS '链路追踪ID';
COMMENT ON COLUMN chat_session.create_time IS '创建时间';
COMMENT ON COLUMN chat_session.update_time IS '更新时间（应用层维护）';

-- 5. 文档元数据表（document_metadata_mapper.xml + DocumentMetadataPO）
CREATE TABLE IF NOT EXISTS document_metadata (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id         VARCHAR(64)   NOT NULL,
  tenant_id           VARCHAR(64)   NOT NULL DEFAULT '',
  owner_user_id       VARCHAR(64)   NOT NULL DEFAULT '',
  knowledge_base_id   VARCHAR(64)   NULL,
  knowledge_base_name VARCHAR(128)  NULL,
  file_name           VARCHAR(256)  NOT NULL,
  file_extension      VARCHAR(16)   NULL,
  file_size           BIGINT        NULL,
  mime_type           VARCHAR(128)  NULL,
  total_chars         INT           NULL,
  total_chunks        INT           NULL,
  section_count       INT           NULL,
  processing_status   VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
  error_message       TEXT          NULL,
  visibility          VARCHAR(16)   NULL,
  deleted_flag        SMALLINT      NOT NULL DEFAULT 0,
  user_id             VARCHAR(64)   NULL,
  create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_document_id UNIQUE (document_id)
);
COMMENT ON TABLE document_metadata IS '文档元数据表';
COMMENT ON COLUMN document_metadata.document_id IS '文档业务ID（唯一）';
COMMENT ON COLUMN document_metadata.tenant_id IS '租户ID';
COMMENT ON COLUMN document_metadata.owner_user_id IS '归属用户ID';
COMMENT ON COLUMN document_metadata.knowledge_base_id IS '所属知识库业务ID';
COMMENT ON COLUMN document_metadata.knowledge_base_name IS '知识库名称快照';
COMMENT ON COLUMN document_metadata.file_name IS '文件名';
COMMENT ON COLUMN document_metadata.file_extension IS '文件扩展名';
COMMENT ON COLUMN document_metadata.file_size IS '文件大小（字节，PO Long→BIGINT）';
COMMENT ON COLUMN document_metadata.mime_type IS 'MIME 类型';
COMMENT ON COLUMN document_metadata.total_chars IS '总字符数（PO Integer→INT）';
COMMENT ON COLUMN document_metadata.total_chunks IS '总分块数（PO Integer→INT）';
COMMENT ON COLUMN document_metadata.section_count IS '章节计数（PO Integer→INT）';
COMMENT ON COLUMN document_metadata.processing_status IS '处理状态：PENDING/PROCESSING/COMPLETED/FAILED';
COMMENT ON COLUMN document_metadata.error_message IS '错误信息';
COMMENT ON COLUMN document_metadata.visibility IS '可见性：PRIVATE/SHARED 等';
COMMENT ON COLUMN document_metadata.deleted_flag IS '软删除标记：0-正常，1-已删除（PO Integer→SMALLINT）';
COMMENT ON COLUMN document_metadata.user_id IS '上传用户标识';
COMMENT ON COLUMN document_metadata.create_time IS '创建时间';
COMMENT ON COLUMN document_metadata.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_doc_scope ON document_metadata (tenant_id, owner_user_id, deleted_flag);

-- 6. 图谱抽取任务表（extraction_task_mapper.xml + ExtractionTaskPO；
--    PO 无自增 id，task_id 即业务主键）
CREATE TABLE IF NOT EXISTS extraction_task (
  task_id             VARCHAR(64) NOT NULL PRIMARY KEY,
  document_id         VARCHAR(64) NOT NULL,
  status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  total_chunks        INT         NOT NULL DEFAULT 0,
  processed_chunks    INT         NOT NULL DEFAULT 0,
  extracted_entities  INT         NOT NULL DEFAULT 0,
  extracted_relations INT         NOT NULL DEFAULT 0,
  error_message       TEXT        NULL,
  created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE extraction_task IS '图谱抽取任务表';
COMMENT ON COLUMN extraction_task.task_id IS '任务业务ID（主键）';
COMMENT ON COLUMN extraction_task.document_id IS '文档业务ID';
COMMENT ON COLUMN extraction_task.status IS '任务状态：PENDING/PROCESSING/COMPLETED/FAILED';
COMMENT ON COLUMN extraction_task.total_chunks IS '总分块数（PO Integer→INT）';
COMMENT ON COLUMN extraction_task.processed_chunks IS '已处理分块数（PO Integer→INT）';
COMMENT ON COLUMN extraction_task.extracted_entities IS '抽取实体数（PO Integer→INT）';
COMMENT ON COLUMN extraction_task.extracted_relations IS '抽取关系数（PO Integer→INT）';
COMMENT ON COLUMN extraction_task.error_message IS '错误信息';
COMMENT ON COLUMN extraction_task.created_at IS '创建时间';
COMMENT ON COLUMN extraction_task.updated_at IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_extraction_doc ON extraction_task (document_id);

-- 7. 知识库表（knowledge_base_mapper.xml + KnowledgeBasePO；
--    document_count 为查询期 COUNT 聚合别名，非物理列）
CREATE TABLE IF NOT EXISTS knowledge_base (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  knowledge_base_id VARCHAR(64)   NOT NULL,
  tenant_id         VARCHAR(64)   NOT NULL DEFAULT '',
  owner_user_id     VARCHAR(64)   NOT NULL DEFAULT '',
  name              VARCHAR(128)  NOT NULL,
  description       VARCHAR(512)  NULL,
  icon              VARCHAR(64)   NULL,
  color             VARCHAR(16)   NULL,
  deleted_flag      SMALLINT      NOT NULL DEFAULT 0,
  create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_kb_id UNIQUE (knowledge_base_id)
);
COMMENT ON TABLE knowledge_base IS '知识库表';
COMMENT ON COLUMN knowledge_base.knowledge_base_id IS '知识库业务ID（唯一）';
COMMENT ON COLUMN knowledge_base.tenant_id IS '租户ID';
COMMENT ON COLUMN knowledge_base.owner_user_id IS '归属用户ID';
COMMENT ON COLUMN knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN knowledge_base.description IS '知识库描述';
COMMENT ON COLUMN knowledge_base.icon IS '图标标识';
COMMENT ON COLUMN knowledge_base.color IS '主题色';
COMMENT ON COLUMN knowledge_base.deleted_flag IS '软删除标记：0-正常，1-已删除（PO Integer→SMALLINT）';
COMMENT ON COLUMN knowledge_base.create_time IS '创建时间';
COMMENT ON COLUMN knowledge_base.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_kb_scope ON knowledge_base (tenant_id, owner_user_id, deleted_flag);

-- 8. RAG 检索追踪表（rag_trace_mapper.xml + RagTracePO）
CREATE TABLE IF NOT EXISTS rag_trace (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  trace_id            VARCHAR(64)   NOT NULL,
  tenant_id           VARCHAR(64)   NOT NULL DEFAULT '',
  owner_user_id       VARCHAR(64)   NOT NULL DEFAULT '',
  session_id          VARCHAR(64)   NULL,
  agent_id            VARCHAR(64)   NULL,
  query_text          TEXT,
  rewrite_text        TEXT,
  retrieval_topk      INT           NULL,
  source_docs         TEXT,
  parent_ids          TEXT,
  parent_texts        TEXT,
  answer_score        DECIMAL(10,6) NULL,
  hallucination_score DECIMAL(10,6) NULL,
  create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE rag_trace IS 'RAG检索追踪表';
COMMENT ON COLUMN rag_trace.trace_id IS '追踪ID（请求维度唯一，未建唯一键：查询带作用域+LIMIT 1）';
COMMENT ON COLUMN rag_trace.tenant_id IS '租户ID';
COMMENT ON COLUMN rag_trace.owner_user_id IS '归属用户ID';
COMMENT ON COLUMN rag_trace.session_id IS '会话ID（AIOps 工具触发时可为空）';
COMMENT ON COLUMN rag_trace.agent_id IS '智能体ID';
COMMENT ON COLUMN rag_trace.query_text IS '原始查询文本';
COMMENT ON COLUMN rag_trace.rewrite_text IS 'Query 改写后的检索文本';
COMMENT ON COLUMN rag_trace.retrieval_topk IS '检索 TopK（PO Integer→INT）';
COMMENT ON COLUMN rag_trace.source_docs IS '命中证据 JSON 字符串（PO String→TEXT）';
COMMENT ON COLUMN rag_trace.parent_ids IS '命中子块对应父块ID列表（JSON 数组文本，工单 0166 父子分块；存量行 NULL）';
COMMENT ON COLUMN rag_trace.parent_texts IS '命中子块对应父块文本列表（JSON 数组文本，工单 0166 父子分块；存量行 NULL）';
COMMENT ON COLUMN rag_trace.answer_score IS '答案质量分（预留，PO Double→DECIMAL(10,6)）';
COMMENT ON COLUMN rag_trace.hallucination_score IS '幻觉率（预留，PO Double→DECIMAL(10,6)）';
COMMENT ON COLUMN rag_trace.create_time IS '创建时间';
CREATE INDEX IF NOT EXISTS idx_rag_trace_trace ON rag_trace (trace_id);
CREATE INDEX IF NOT EXISTS idx_rag_trace_scope ON rag_trace (tenant_id, owner_user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_rag_trace_session ON rag_trace (session_id, create_time);

-- 存量库迁移（工单 0166 父子分块，rag_trace 增列；ADD COLUMN IF NOT EXISTS 幂等可重复执行）
ALTER TABLE rag_trace ADD COLUMN IF NOT EXISTS parent_ids   TEXT;
ALTER TABLE rag_trace ADD COLUMN IF NOT EXISTS parent_texts TEXT;
COMMENT ON COLUMN rag_trace.parent_ids IS '命中子块对应父块ID列表（JSON 数组文本，工单 0166 父子分块）';
COMMENT ON COLUMN rag_trace.parent_texts IS '命中子块对应父块文本列表（JSON 数组文本，工单 0166 父子分块）';

-- 9. 用户表（user_mapper.xml + UserPO；PG 保留字 user，建表须带双引号 "user"）
CREATE TABLE IF NOT EXISTS "user" (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username    VARCHAR(64)  NOT NULL,
  password    VARCHAR(100) NOT NULL,
  phone       VARCHAR(20)  NULL,
  email       VARCHAR(128) NULL,
  nickname    VARCHAR(64)  NULL,
  avatar      VARCHAR(512) NULL,
  role        VARCHAR(16)  NOT NULL DEFAULT 'USER',
  status      SMALLINT     NOT NULL DEFAULT 1,
  create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_username UNIQUE (username)
);
COMMENT ON TABLE "user" IS '用户表（PG 保留字 user，SQL 中须以 "user" 引用）';
COMMENT ON COLUMN "user".username IS '用户名（唯一，登录凭证）';
COMMENT ON COLUMN "user".password IS '密码哈希（BCrypt）';
COMMENT ON COLUMN "user".phone IS '手机号';
COMMENT ON COLUMN "user".email IS '邮箱';
COMMENT ON COLUMN "user".nickname IS '昵称';
COMMENT ON COLUMN "user".avatar IS '头像地址';
COMMENT ON COLUMN "user".role IS '角色：ADMIN/USER 等';
COMMENT ON COLUMN "user".status IS '状态：0-禁用，1-启用（PO Integer→SMALLINT）';
COMMENT ON COLUMN "user".create_time IS '创建时间';
COMMENT ON COLUMN "user".update_time IS '更新时间（应用层维护）';

-- 10. Agent 记忆表（注解 mapper AgentMemoryMapper.java + AgentMemoryPO；
--     metadata 为 PO Map + JacksonTypeHandler 以 JSON 文本读写 → PG TEXT 不用 jsonb）
CREATE TABLE IF NOT EXISTS agent_memory (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  memory_id    VARCHAR(64)   NOT NULL,
  tenant_id    VARCHAR(64)   NOT NULL DEFAULT '',
  user_id      VARCHAR(64)   NOT NULL DEFAULT '',
  agent_id     VARCHAR(64)   NOT NULL DEFAULT '',
  session_id   VARCHAR(64)   NULL,
  content      TEXT          NOT NULL,
  content_hash VARCHAR(64)   NOT NULL DEFAULT '',
  memory_type  VARCHAR(32)   NOT NULL DEFAULT 'FACT',
  scope        VARCHAR(256)  NOT NULL DEFAULT '/',
  importance   DECIMAL(3,2)  NOT NULL DEFAULT 0.50,
  source       VARCHAR(32)   NULL,
  metadata     TEXT          NULL,
  status       SMALLINT      NOT NULL DEFAULT 1,
  expires_at   TIMESTAMP     NULL,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_memory_id UNIQUE (memory_id),
  CONSTRAINT uk_content_tenant_user UNIQUE (content_hash, tenant_id, user_id)
);
COMMENT ON TABLE agent_memory IS 'Agent记忆表';
COMMENT ON COLUMN agent_memory.memory_id IS '记忆业务ID（唯一，定位/软删/更新依据）';
COMMENT ON COLUMN agent_memory.tenant_id IS '租户ID';
COMMENT ON COLUMN agent_memory.user_id IS '用户ID';
COMMENT ON COLUMN agent_memory.agent_id IS '智能体ID';
COMMENT ON COLUMN agent_memory.session_id IS '会话ID（可为空）';
COMMENT ON COLUMN agent_memory.content IS '记忆内容';
COMMENT ON COLUMN agent_memory.content_hash IS '内容哈希（去重键成员）';
COMMENT ON COLUMN agent_memory.memory_type IS '记忆类型：FACT 等';
COMMENT ON COLUMN agent_memory.scope IS '作用域路径（前缀匹配，如 /agent/session）';
COMMENT ON COLUMN agent_memory.importance IS '重要度 0-1（PO Float→DECIMAL(3,2)）';
COMMENT ON COLUMN agent_memory.source IS '记忆来源';
COMMENT ON COLUMN agent_memory.metadata IS '扩展元数据（PO Map + JacksonTypeHandler，JSON 文本读写→TEXT 不用 jsonb）';
COMMENT ON COLUMN agent_memory.status IS '状态：0-已删除（软删），1-有效（PO Integer→SMALLINT）';
COMMENT ON COLUMN agent_memory.expires_at IS '过期时间，NULL 永不过期';
COMMENT ON COLUMN agent_memory.created_at IS '创建时间';
COMMENT ON COLUMN agent_memory.updated_at IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_memory_tenant_user ON agent_memory (tenant_id, user_id, status);
CREATE INDEX IF NOT EXISTS idx_memory_scope ON agent_memory (scope);
CREATE INDEX IF NOT EXISTS idx_memory_expire ON agent_memory (tenant_id, user_id, expires_at);

-- =============================================================================
-- pgvector 向量表（工单 0129，三期 Milvus→pgvector；canonical DDL 由
-- PgVectorVectorStoreRepository.ensureCollection 幂等维护，此处为登记副本）
-- =============================================================================

-- RAG 文档块向量（替代 Milvus biz collection：IVF_FLAT+L2 → HNSW halfvec_l2_ops）
CREATE TABLE IF NOT EXISTS biz_chunks (
  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  content   TEXT   NOT NULL,
  metadata  JSONB  NOT NULL DEFAULT '{}'::jsonb,
  embedding halfvec(2048)
);
COMMENT ON TABLE biz_chunks IS 'RAG 文档块向量（租户过滤经 metadata @> + GIN）';
COMMENT ON COLUMN biz_chunks.embedding IS 'halfvec：dim 2048 超 vector 索引上限 2000';
CREATE INDEX IF NOT EXISTS idx_biz_chunks_hnsw ON biz_chunks USING hnsw (embedding halfvec_l2_ops) WITH (m = 16, ef_construction = 200);
CREATE INDEX IF NOT EXISTS idx_biz_chunks_metadata ON biz_chunks USING gin (metadata jsonb_path_ops);
-- 过滤+ANN 后过滤少召回：库级开启 iterative scan（需库 owner；应用启动幂等设置）
-- ALTER DATABASE <db> SET hnsw.iterative_scan = strict_order;

-- Agent 记忆向量（工单 0130；canonical DDL 由 AgentMemoryPgVectorRepository @PostConstruct 幂等维护）
-- 相似度语义：1 - (embedding <=> q)，[0,1] 越大越好（对齐 Milvus COSINE 分数）
CREATE TABLE IF NOT EXISTS agent_memory_vec (
  memory_id    VARCHAR(64) PRIMARY KEY,
  content      TEXT,
  tenant_id    VARCHAR(64),
  user_id      VARCHAR(64),
  agent_id     VARCHAR(64),
  scope        VARCHAR(255),
  memory_type  VARCHAR(32),
  importance   REAL,
  content_hash VARCHAR(64),
  created_at   BIGINT,
  embedding    halfvec(2048)
);
COMMENT ON TABLE agent_memory_vec IS 'Agent 记忆向量（HNSW COSINE；scope 前缀 LIKE 过滤）';
CREATE INDEX IF NOT EXISTS idx_amv_hnsw ON agent_memory_vec USING hnsw (embedding halfvec_cosine_ops) WITH (m = 16, ef_construction = 200);

-- =============================================================================
-- 11. 租户知识库配额表（工单 0168：tenant_knowledge_quota_mapper.xml +
--     TenantKnowledgeQuotaPO；表中无对应租户行 = 不限制（存量兼容），
--     行内 max_documents / max_chunks 为 NULL 同样视为不限制）
-- =============================================================================
CREATE TABLE IF NOT EXISTS tenant_knowledge_quota (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     VARCHAR(64) NOT NULL,
  max_documents INT         NULL,
  max_chunks    INT         NULL,
  create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_quota_tenant UNIQUE (tenant_id)
);
COMMENT ON TABLE tenant_knowledge_quota IS '租户知识库配额表';
COMMENT ON COLUMN tenant_knowledge_quota.tenant_id IS '租户ID（唯一定位，未配置租户=不限制）';
COMMENT ON COLUMN tenant_knowledge_quota.max_documents IS '文档数上限（NULL=不限制，PO Integer→INT）';
COMMENT ON COLUMN tenant_knowledge_quota.max_chunks IS '分块数上限（NULL=不限制，PO Integer→INT）';
COMMENT ON COLUMN tenant_knowledge_quota.create_time IS '创建时间';
COMMENT ON COLUMN tenant_knowledge_quota.update_time IS '更新时间（应用层维护）';

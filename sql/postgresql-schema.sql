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

-- 14. 工作流运行表（工单 0212 AB9：run 级摘要 + 节点级明细 JSON 文本）
CREATE TABLE IF NOT EXISTS workflow_run (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id           VARCHAR(64)  NOT NULL,
    workflow_name    VARCHAR(128) NOT NULL,
    workflow_version INT          NOT NULL DEFAULT 1,
    tenant_id        VARCHAR(64),
    status           VARCHAR(16)  NOT NULL,
    failed_node_id   VARCHAR(128),
    error            VARCHAR(512),
    duration_ms      BIGINT,
    node_runs_json   TEXT,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_run_id UNIQUE (run_id)
);
COMMENT ON TABLE workflow_run IS '工作流运行历史（AB9：run 级摘要 + 节点明细 JSON；执行引擎落档）';
COMMENT ON COLUMN workflow_run.status IS '状态：COMPLETED/FAILED/INTERRUPTED';
COMMENT ON COLUMN workflow_run.node_runs_json IS '节点级明细 JSON 数组（nodeId/status/attempts/durationMs/error）';
CREATE INDEX IF NOT EXISTS idx_workflow_run_name ON workflow_run (workflow_name, create_time);

-- 15. 检索参数画像表（工单 0235 AE8：配置组合 + 命中率/延迟样本统计）
CREATE TABLE IF NOT EXISTS retrieval_profile (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_key   VARCHAR(128) NOT NULL,
    samples       INT          NOT NULL DEFAULT 0,
    avg_hit_rate  DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
    p95_latency_ms BIGINT      NOT NULL DEFAULT 0,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_retrieval_profile_key UNIQUE (profile_key)
);
COMMENT ON TABLE retrieval_profile IS '检索参数画像（AE8：topK/efSearch/权重组合的命中率与延迟快照）';

-- 16. 工作流蓝图模板表（工单 0268 AI1：常用编排固化为可实例化模板）
CREATE TABLE IF NOT EXISTS workflow_blueprint (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(512),
    category         VARCHAR(64)  NOT NULL DEFAULT 'general',
    tags             VARCHAR(256),
    graph_json       TEXT         NOT NULL,
    param_schema_json TEXT,
    operator         VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_blueprint_name UNIQUE (name)
);
COMMENT ON TABLE workflow_blueprint IS '工作流蓝图模板（AI1：图定义 DSL + 参数 schema，实例化产出可注册图定义）';

-- 17. 图谱索引表（工单 0308 AM3：AM1 图索引构建快照落档）
CREATE TABLE IF NOT EXISTS graph_index (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    index_id      VARCHAR(64)  NOT NULL,
    document_id   VARCHAR(128) NOT NULL,
    unit_count    INT          NOT NULL DEFAULT 0,
    node_count    INT          NOT NULL DEFAULT 0,
    edge_count    INT          NOT NULL DEFAULT 0,
    index_hash    VARCHAR(64)  NOT NULL,
    graph_json    TEXT,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_graph_index_id UNIQUE (index_id)
);
COMMENT ON TABLE graph_index IS '图谱索引快照（AM1：text_units/节点/边/来源块映射哈希）';
COMMENT ON COLUMN graph_index.index_hash IS '规范化序列化 SHA-256（重放校验）';
COMMENT ON COLUMN graph_index.update_time IS '更新时间（应用层维护）';

-- 18. 图谱社区表（工单 0308 AM3：社区划分 + C0/C1/C2 分层摘要）
CREATE TABLE IF NOT EXISTS graph_community (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    index_id      VARCHAR(64)  NOT NULL,
    community_id  VARCHAR(64)  NOT NULL,
    level         INT          NOT NULL DEFAULT 0,
    summary_text  TEXT,
    member_keys   TEXT,
    member_count  INT          NOT NULL DEFAULT 0,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_graph_community UNIQUE (index_id, community_id, level)
);
COMMENT ON TABLE graph_community IS '图谱社区与分层摘要（AM3：C0 基础/C1 聚合/C2 顶层）';
COMMENT ON COLUMN graph_community.level IS '层级：0=C0 基础社区，1=C1 聚合，2=C2 顶层';
COMMENT ON COLUMN graph_community.update_time IS '更新时间（应用层维护）';

-- 19. 浏览器任务模板表（工单 0344 AQ6：目标+参数占位符+动作序列模板）
CREATE TABLE IF NOT EXISTS browser_task_template (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    goal          VARCHAR(512),
    parameters    TEXT,
    actions_json  TEXT         NOT NULL,
    tenant_id     VARCHAR(64),
    operator      VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_browser_template_name UNIQUE (name)
);
COMMENT ON TABLE browser_task_template IS '浏览器任务模板（AQ6：{{param}} 占位实例化 + 录制序列回放校验）';
COMMENT ON COLUMN browser_task_template.update_time IS '更新时间（应用层维护）';

-- 20. 研究任务表（工单 0352/0353 AR6-AR7：状态机 + 检查点快照）
CREATE TABLE IF NOT EXISTS research_task (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id        VARCHAR(64)  NOT NULL,
    topic          VARCHAR(256) NOT NULL,
    perspectives   VARCHAR(256),
    status         VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
    checkpoint_json TEXT,
    token_cost     BIGINT       NOT NULL DEFAULT 0,
    duration_ms    BIGINT       NOT NULL DEFAULT 0,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_research_task_id UNIQUE (task_id)
);
COMMENT ON TABLE research_task IS '研究任务（AR6：CREATED→PLANNING→SEARCHING→DRAFTING→CITING→DONE/FAILED/CANCELLED + 检查点续跑）';
COMMENT ON COLUMN research_task.update_time IS '更新时间（应用层维护）';

-- 21. 研究报告表（工单 0353 AR7：Markdown + 引用表 + 元数据）
CREATE TABLE IF NOT EXISTS research_report (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id        VARCHAR(64)  NOT NULL,
    topic          VARCHAR(256) NOT NULL,
    markdown       TEXT         NOT NULL,
    citations_json TEXT,
    metadata_json  TEXT,
    citation_rate  DOUBLE PRECISION NOT NULL DEFAULT 0,
    partial        BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_research_report_task UNIQUE (task_id)
);
COMMENT ON TABLE research_report IS '研究报告（AR7：Markdown+引用表+元数据 JSON，静态可交付）';
COMMENT ON COLUMN research_report.update_time IS '更新时间（应用层维护）';

-- 22. 时序记忆事实边表（工单 0362 AS1：bi-temporal 双时间线）
CREATE TABLE IF NOT EXISTS tmemory_edge (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    edge_id        VARCHAR(64)  NOT NULL,
    subject        VARCHAR(256) NOT NULL,
    predicate      VARCHAR(128) NOT NULL,
    object_entity  VARCHAR(256) NOT NULL,
    valid_from     BIGINT       NOT NULL,
    valid_to       BIGINT,
    invalid_reason VARCHAR(32),
    ingest_seq     BIGINT       NOT NULL,
    confidence     DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    source         VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    kind           VARCHAR(16)  NOT NULL DEFAULT 'EPISODIC',
    access_count   INT          NOT NULL DEFAULT 0,
    score          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    promoted_from  VARCHAR(512),
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tmemory_edge_id UNIQUE (edge_id)
);
COMMENT ON TABLE tmemory_edge IS '时序记忆事实边（AS1：bi-temporal 双时间线，valid_to 为空表示仍有效）';
COMMENT ON COLUMN tmemory_edge.update_time IS '更新时间（应用层维护）';

-- 23. 语音转写任务表（工单 0386 AU8：ASR 端口产物持久化）
CREATE TABLE IF NOT EXISTS speech_transcript (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transcript_id  VARCHAR(64)  NOT NULL,
    audio_ref      VARCHAR(512) NOT NULL,
    language       VARCHAR(16)  NOT NULL DEFAULT 'zh',
    segments_json  TEXT,
    metrics_json   TEXT,
    duration_ms    BIGINT       NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL DEFAULT 'DONE',
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_speech_transcript_id UNIQUE (transcript_id)
);
COMMENT ON TABLE speech_transcript IS '语音转写任务（AU8：ASR 端口产物，段/指标 JSON）';
COMMENT ON COLUMN speech_transcript.update_time IS '更新时间（应用层维护）';

-- 24. 文档解析任务表（工单 0395 AV9：版面结构 + 质量记分持久化）
CREATE TABLE IF NOT EXISTS doc_parse_task (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id        VARCHAR(64)  NOT NULL,
    doc_ref        VARCHAR(512) NOT NULL,
    page           INT          NOT NULL DEFAULT 1,
    status         VARCHAR(16)  NOT NULL DEFAULT 'DONE',
    layout_json    TEXT,
    score_json     TEXT,
    parse_ms       BIGINT       NOT NULL DEFAULT 0,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_doc_parse_task_id UNIQUE (task_id)
);
COMMENT ON TABLE doc_parse_task IS '文档解析任务（AV9：版面结构+记分 JSON）';
COMMENT ON COLUMN doc_parse_task.update_time IS '更新时间（应用层维护）';

-- 25. 搜索索引文档表（工单 0404 AW9：searchkernel 进程内倒排的持久化面）
CREATE TABLE IF NOT EXISTS search_index_doc (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id         VARCHAR(128) NOT NULL,
    index_name     VARCHAR(64)  NOT NULL DEFAULT 'default',
    title          VARCHAR(256),
    body           TEXT,
    fields_json    TEXT,
    version        BIGINT       NOT NULL DEFAULT 1,
    deleted        BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_search_doc_id UNIQUE (doc_id)
);
COMMENT ON TABLE search_index_doc IS '搜索索引文档（AW9：版本单调+墓碑）';
COMMENT ON COLUMN search_index_doc.update_time IS '更新时间（应用层维护）';

-- 26. 爬取清单表（工单 0425 AY7：crawler 域 URL 状态机）
CREATE TABLE IF NOT EXISTS crawl_url (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fingerprint    VARCHAR(64)  NOT NULL,
    raw_url        VARCHAR(768) NOT NULL,
    domain         VARCHAR(128) NOT NULL DEFAULT '',
    depth          INT          NOT NULL DEFAULT 0,
    priority       INT          NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count    INT          NOT NULL DEFAULT 0,
    content_hash   VARCHAR(64),
    fetched_at     BIGINT,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_crawl_url_fp UNIQUE (fingerprint)
);
COMMENT ON TABLE crawl_url IS '爬取清单（AY7：PENDING→FETCHED/FAILED 状态机，robots 合规先行）';
COMMENT ON COLUMN crawl_url.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_crawl_url_status ON crawl_url (status, depth);

-- 27. 代码编辑审计表（工单 0434 AZ8：codeintel 域编辑留痕）
CREATE TABLE IF NOT EXISTS codeintel_edit (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    edit_id        VARCHAR(64)  NOT NULL,
    file_path      VARCHAR(512) NOT NULL,
    strategy       VARCHAR(16)  NOT NULL,
    success        BOOLEAN      NOT NULL DEFAULT FALSE,
    errors_json    TEXT,
    checkpoint_id  VARCHAR(64),
    cost_ms        BIGINT       NOT NULL DEFAULT 0,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_codeintel_edit_id UNIQUE (edit_id)
);
COMMENT ON TABLE codeintel_edit IS '代码编辑审计（AZ8：search-replace/unified diff 编辑留痕+检查点回滚）';
COMMENT ON COLUMN codeintel_edit.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_codeintel_edit_file ON codeintel_edit (file_path, create_time);

-- 28. 向量点表（工单 0441 BA7：vectorkernel 进程内 HNSW 的持久化面）
CREATE TABLE IF NOT EXISTS vector_point (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    point_id       VARCHAR(64)  NOT NULL,
    vector_json    TEXT         NOT NULL,
    tags_json      TEXT,
    dimension      INT          NOT NULL,
    quantized      BOOLEAN      NOT NULL DEFAULT FALSE,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT       NOT NULL DEFAULT 1,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_vector_point_id UNIQUE (point_id)
);
COMMENT ON TABLE vector_point IS '向量点（BA7：ACTIVE/TOMBSTONED 墓碑+快照重建等价）';
COMMENT ON COLUMN vector_point.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_vector_point_status ON vector_point (status);

-- 29. 扫描发现表（工单 0457 BC7：scankernel 发现清单）
CREATE TABLE IF NOT EXISTS scan_finding (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fingerprint    VARCHAR(64)  NOT NULL,
    rule_id        VARCHAR(128) NOT NULL,
    file_path      VARCHAR(512) NOT NULL,
    line_no        INT          NOT NULL,
    severity       VARCHAR(8)   NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    snippet        TEXT,
    batch_id       VARCHAR(64)  NOT NULL,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_scan_finding_fp UNIQUE (fingerprint)
);
COMMENT ON TABLE scan_finding IS '扫描发现（BC7：指纹唯一+批次幂等+抑制/基线状态）';
COMMENT ON COLUMN scan_finding.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_scan_finding_rule ON scan_finding (rule_id, status);

-- 30. 存储段表（工单 0478 BE7：storekernel LSM 段元数据）
CREATE TABLE IF NOT EXISTS store_segment (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    segment_id  BIGINT       NOT NULL,
    level_no    INT          NOT NULL,
    min_key     VARCHAR(512) NOT NULL,
    max_key     VARCHAR(512) NOT NULL,
    row_count   INT          NOT NULL,
    byte_size   BIGINT       NOT NULL,
    checksum    VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_store_segment_id UNIQUE (segment_id)
);
COMMENT ON TABLE store_segment IS '存储段（BE7：LSM 段元数据+compaction 淘汰状态）';
COMMENT ON COLUMN store_segment.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_store_segment_level ON store_segment (level_no, status);

-- 31. 文本文档表（工单 0494 BG7：textkernel 检索文档）
CREATE TABLE IF NOT EXISTS text_doc (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id      INT          NOT NULL,
    field_text  JSONB        NOT NULL,
    term_count  INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'INDEXED',
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_text_doc_id UNIQUE (doc_id)
);
COMMENT ON TABLE text_doc IS '文本文档（BG7：字段文本 JSON+词数+状态）';
COMMENT ON COLUMN text_doc.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_text_doc_status ON text_doc (status);

-- 32. 任务表（工单 0502 BH7：jobkernel 调度任务）
CREATE TABLE IF NOT EXISTS job_task (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id         VARCHAR(128) NOT NULL,
    cron_expr       VARCHAR(64)  NOT NULL,
    shard_index     INT          NOT NULL DEFAULT 0,
    shard_total     INT          NOT NULL DEFAULT 1,
    status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    last_trigger_at BIGINT,
    next_trigger_at BIGINT,
    retry_count     INT          NOT NULL DEFAULT 0,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_task_id UNIQUE (task_id)
);
COMMENT ON TABLE job_task IS '调度任务（BH7：cron+分片+重试计数+状态）';
COMMENT ON COLUMN job_task.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_job_task_status ON job_task (status, next_trigger_at);

-- 33. 分词词条表（工单 0531 BK7：segkernel 分词词条）
CREATE TABLE IF NOT EXISTS seg_term (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    word        VARCHAR(64)  NOT NULL,
    freq        BIGINT       NOT NULL,
    word_length INT          NOT NULL,
    source      VARCHAR(16)  NOT NULL DEFAULT 'DICT',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_seg_term_word UNIQUE (word)
);
COMMENT ON TABLE seg_term IS '分词词条（BK7：词频+来源+状态）';
COMMENT ON COLUMN seg_term.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_seg_term_source ON seg_term (source, status);

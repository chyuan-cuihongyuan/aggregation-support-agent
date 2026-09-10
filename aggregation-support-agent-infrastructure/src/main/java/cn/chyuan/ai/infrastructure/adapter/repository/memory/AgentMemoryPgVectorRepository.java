package cn.chyuan.ai.infrastructure.adapter.repository.memory;

import cn.chyuan.ai.domain.memory.adapter.repository.IAgentMemoryRepository;
import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryEntry;
import cn.chyuan.ai.domain.memory.model.valobj.TenantUserPair;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.infrastructure.config.PgVectorConfigProperties;
import cn.chyuan.ai.infrastructure.persistent.mapper.memory.AgentMemoryMapper;
import cn.chyuan.ai.infrastructure.dao.po.memory.AgentMemoryPO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 记忆 pgvector 仓储（工单 0130，三期 Milvus→pgvector 替换）。
 *
 * <p>组合仓储：关系面沿用 AgentMemoryMapper（MySQL/PG 双轨由 P2 改造收敛），
 * 向量面走 agent_memory_vec 表（halfvec + HNSW COSINE）。
 *
 * <p>分数语义与 Milvus 时代严格对齐：<b>相似度 = 1 - 余弦距离，[0,1] 越大越好</b>
 * （GREATEST(0,…) 保护；下游 0.7/0.85/0.90 阈值与 DefaultAgentMemoryService 行为零漂移）。
 * 与 Milvus 版的差异收益：getEmbedding 直读向量列（无需重新调 embedding 接口）；
 * updateEmbedding 用 UPDATE（Milvus 只能删+插）。
 */
@Slf4j
@Primary
@Repository
@ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true", matchIfMissing = true)
public class AgentMemoryPgVectorRepository implements IAgentMemoryRepository {

    private static final String SQL_UPSERT = """
            INSERT INTO agent_memory_vec
              (memory_id, embedding, content, tenant_id, user_id, agent_id, scope, memory_type, importance, content_hash, created_at)
            VALUES (?, CAST(? AS halfvec), ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (memory_id) DO UPDATE SET
              embedding = EXCLUDED.embedding, content = EXCLUDED.content, content_hash = EXCLUDED.content_hash""";

    /** 相似度别名列不可用于索引匹配（函数变换不反演），ORDER BY 必须用原始算子表达式 */
    private static final String SQL_SEARCH = """
            SELECT memory_id, content, tenant_id, user_id, agent_id, scope, memory_type,
                   importance, content_hash, created_at,
                   GREATEST(0, 1 - (embedding <=> CAST(? AS halfvec))) AS similarity
            FROM agent_memory_vec
            WHERE tenant_id = ? AND user_id = ?%s
            ORDER BY embedding <=> CAST(? AS halfvec)
            LIMIT ?""";

    private static final String SQL_UPDATE_EMBEDDING =
            "UPDATE agent_memory_vec SET embedding = CAST(? AS halfvec) WHERE memory_id = ?";
    private static final String SQL_DELETE_EMBEDDING = "DELETE FROM agent_memory_vec WHERE memory_id = ?";
    private static final String SQL_SELECT_EMBEDDING = "SELECT embedding::text FROM agent_memory_vec WHERE memory_id = ?";

    @Resource
    private AgentMemoryMapper agentMemoryMapper;

    @Resource
    private IEmbeddingService embeddingService;

    private final JdbcTemplate vectorJdbc;
    private final PgVectorConfigProperties properties;

    public AgentMemoryPgVectorRepository(@Qualifier("vectorJdbc") JdbcTemplate vectorJdbc,
            PgVectorConfigProperties properties) {
        this.vectorJdbc = vectorJdbc;
        this.properties = properties;
    }

    /** 幂等建表建索引（对齐 Milvus 版 @PostConstruct init 语义） */
    @PostConstruct
    void ensureTable() {
        try {
            vectorJdbc.execute("""
                    CREATE TABLE IF NOT EXISTS agent_memory_vec (
                      memory_id   VARCHAR(64) PRIMARY KEY,
                      content     TEXT,
                      tenant_id   VARCHAR(64),
                      user_id     VARCHAR(64),
                      agent_id    VARCHAR(64),
                      scope       VARCHAR(255),
                      memory_type VARCHAR(32),
                      importance  REAL,
                      content_hash VARCHAR(64),
                      created_at  BIGINT,
                      embedding   halfvec(%d)
                    )""".formatted(properties.getDimension()));
            vectorJdbc.execute(String.format(
                    "CREATE INDEX IF NOT EXISTS idx_amv_hnsw ON agent_memory_vec"
                            + " USING hnsw (embedding halfvec_cosine_ops) WITH (m = %d, ef_construction = %d)",
                    properties.getM(), properties.getEfConstruction()));
            log.info("[pgvector] Agent 记忆向量表就绪: agent_memory_vec（dim={}，HNSW COSINE）",
                    properties.getDimension());
        } catch (Exception e) {
            log.error("[pgvector] agent_memory_vec 初始化失败: {}", e.getMessage());
        }
    }

    // ───────────────────────── 关系面（与 Milvus 版同构，走 mapper） ─────────────────────────

    @Override
    public void save(AgentMemoryEntity entity) {
        AgentMemoryPO po = convertToPO(entity);
        try {
            agentMemoryMapper.insert(po);
        } catch (DuplicateKeyException e) {
            // 唯一键 uk_content_tenant_user 冲突：视为幂等成功
            log.debug("记忆已存在(唯一键冲突)，跳过: hash={}", entity.getContentHash());
        }
    }

    @Override
    public void saveBatch(List<AgentMemoryEntity> entities) {
        for (AgentMemoryEntity entity : entities) {
            save(entity);
        }
    }

    @Override
    public AgentMemoryEntity findByMemoryId(String memoryId) {
        AgentMemoryPO po = agentMemoryMapper.selectActiveByMemoryId(memoryId);
        return po != null ? convertToEntity(po) : null;
    }

    @Override
    public boolean existsByContentHash(String contentHash, String tenantId, String userId, String scope) {
        return agentMemoryMapper.countByContentHash(contentHash, tenantId, userId, scope) > 0;
    }

    @Override
    public List<AgentMemoryEntity> findByTenantAndUser(String tenantId, String userId) {
        return agentMemoryMapper.selectActiveByTenantAndUser(tenantId, userId).stream()
                .map(this::convertToEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentMemoryEntity> findByScope(String scope) {
        return agentMemoryMapper.selectActiveByScope(scope).stream()
                .map(this::convertToEntity)
                .collect(Collectors.toList());
    }

    @Override
    public void updateContent(String memoryId, String content, String contentHash) {
        agentMemoryMapper.updateContent(memoryId, content, contentHash);
    }

    @Override
    public void softDelete(String memoryId) {
        agentMemoryMapper.softDelete(memoryId);
        deleteEmbedding(memoryId);
    }

    @Override
    public int deleteExpired(String tenantId, String userId) {
        return agentMemoryMapper.deleteExpired(tenantId, userId);
    }

    @Override
    public List<TenantUserPair> findAllTenantUserPairs() {
        return agentMemoryMapper.selectDistinctTenantUserPairs().stream()
                .map(row -> TenantUserPair.builder()
                        .tenantId((String) row.get("tenant_id"))
                        .userId((String) row.get("user_id"))
                        .build())
                .collect(Collectors.toList());
    }

    // ───────────────────────── 向量面（pgvector） ─────────────────────────

    @Override
    public List<MemoryEntry> searchSimilar(String content, String tenantId, String userId, String scope, int limit) {
        float[] embedding = embeddingService.embed(content);
        List<AgentMemoryEntity> results = search(embedding, tenantId, userId, scope, limit);
        // similarity 已在向量侧算好（1 - cosine 距离），无需重新嵌入
        return results.stream()
                .map(entry -> MemoryEntry.builder()
                        .entry(entry)
                        .score(entry.getSearchScore() != null ? entry.getSearchScore() : 0.0)
                        .build())
                .filter(e -> e.getScore() > 0.7)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentMemoryEntity> search(float[] queryEmbedding, String tenantId, String userId,
            String scope, int limit) {
        try {
            String q = toLiteral(queryEmbedding);
            String scopeClause = (scope != null && !scope.isEmpty())
                    ? " AND scope LIKE ?" : "";
            String sql = SQL_SEARCH.formatted(scopeClause);

            List<Object> args = new ArrayList<>();
            args.add(q);
            args.add(tenantId);
            args.add(userId);
            if (!scopeClause.isEmpty()) {
                args.add(scope + "%");
            }
            args.add(q);
            args.add(limit);

            return vectorJdbc.query(sql, (rs, rowNum) -> AgentMemoryEntity.builder()
                    .memoryId(rs.getString("memory_id"))
                    .content(rs.getString("content"))
                    .tenantId(rs.getString("tenant_id"))
                    .userId(rs.getString("user_id"))
                    .agentId(rs.getString("agent_id"))
                    .scope(rs.getString("scope"))
                    .memoryType(MemoryType.valueOf(rs.getString("memory_type")))
                    .importance(rs.getFloat("importance"))
                    .contentHash(rs.getString("content_hash"))
                    .createdAt(Instant.ofEpochMilli(rs.getLong("created_at")))
                    // 相似度 [0,1] 越大越好，语义同 Milvus COSINE 分数
                    .searchScore(rs.getDouble("similarity"))
                    .build(), args.toArray());
        } catch (Exception e) {
            log.error("Agent Memory pgvector 搜索异常", e);
            return List.of();
        }
    }

    /** 直读向量列（Milvus 取不出向量只能重新 embed 的路径优化点） */
    @Override
    public float[] getEmbedding(String memoryId) {
        try {
            List<String> rows = vectorJdbc.queryForList(SQL_SELECT_EMBEDDING, String.class, memoryId);
            if (rows.isEmpty()) {
                return new float[0];
            }
            return parseLiteral(rows.get(0));
        } catch (Exception e) {
            log.error("读取记忆向量失败: {}", memoryId, e);
            return new float[0];
        }
    }

    @Override
    public void insertWithEmbedding(AgentMemoryEntity entity, float[] embedding) {
        try {
            vectorJdbc.update(SQL_UPSERT,
                    entity.getMemoryId(),
                    toLiteral(embedding),
                    entity.getContent(),
                    entity.getTenantId(),
                    entity.getUserId(),
                    entity.getAgentId() != null ? entity.getAgentId() : "",
                    entity.getScope(),
                    entity.getMemoryType().name(),
                    entity.getImportance(),
                    entity.getContentHash(),
                    entity.getCreatedAt() != null ? entity.getCreatedAt().toEpochMilli() : System.currentTimeMillis());
        } catch (Exception e) {
            log.error("Agent Memory 向量写入异常: {}", entity.getMemoryId(), e);
            throw new RuntimeException("Agent Memory 向量写入失败", e);
        }
    }

    /** UPDATE 直改（Milvus 不支持更新只能删+插，PG 原生优势） */
    @Override
    public void updateEmbedding(String memoryId, float[] embedding) {
        vectorJdbc.update(SQL_UPDATE_EMBEDDING, toLiteral(embedding), memoryId);
    }

    @Override
    public void deleteEmbedding(String memoryId) {
        try {
            vectorJdbc.update(SQL_DELETE_EMBEDDING, memoryId);
        } catch (Exception e) {
            log.warn("Agent Memory 向量删除异常: {}", memoryId, e);
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    static String toLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /** '[0.1,0.2,...]' → float[]（getEmbedding 直读解析） */
    static float[] parseLiteral(String literal) {
        if (literal == null) {
            return new float[0];
        }
        String body = literal.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        body = body.trim();
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }

    private AgentMemoryPO convertToPO(AgentMemoryEntity entity) {
        AgentMemoryPO po = new AgentMemoryPO();
        po.setMemoryId(entity.getMemoryId());
        po.setTenantId(entity.getTenantId());
        po.setUserId(entity.getUserId());
        po.setAgentId(entity.getAgentId());
        po.setSessionId(entity.getSessionId());
        po.setContent(entity.getContent());
        po.setContentHash(entity.getContentHash());
        po.setMemoryType(entity.getMemoryType().name());
        po.setScope(entity.getScope());
        po.setImportance(entity.getImportance());
        po.setSource(entity.getSource());
        po.setMetadata(entity.getMetadata());
        po.setStatus(entity.getStatus());
        if (entity.getExpiresAt() != null) {
            po.setExpiresAt(LocalDateTime.ofInstant(entity.getExpiresAt(), ZoneId.systemDefault()));
        }
        if (entity.getCreatedAt() != null) {
            po.setCreatedAt(LocalDateTime.ofInstant(entity.getCreatedAt(), ZoneId.systemDefault()));
        }
        if (entity.getUpdatedAt() != null) {
            po.setUpdatedAt(LocalDateTime.ofInstant(entity.getUpdatedAt(), ZoneId.systemDefault()));
        }
        return po;
    }

    private AgentMemoryEntity convertToEntity(AgentMemoryPO po) {
        AgentMemoryEntity entity = AgentMemoryEntity.builder()
                .id(po.getId())
                .memoryId(po.getMemoryId())
                .tenantId(po.getTenantId())
                .userId(po.getUserId())
                .agentId(po.getAgentId())
                .sessionId(po.getSessionId())
                .content(po.getContent())
                .contentHash(po.getContentHash())
                .memoryType(MemoryType.valueOf(po.getMemoryType()))
                .scope(po.getScope())
                .importance(po.getImportance())
                .source(po.getSource())
                .metadata(po.getMetadata())
                .status(po.getStatus())
                .build();
        if (po.getExpiresAt() != null) {
            entity.setExpiresAt(po.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        if (po.getCreatedAt() != null) {
            entity.setCreatedAt(po.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        if (po.getUpdatedAt() != null) {
            entity.setUpdatedAt(po.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        return entity;
    }
}

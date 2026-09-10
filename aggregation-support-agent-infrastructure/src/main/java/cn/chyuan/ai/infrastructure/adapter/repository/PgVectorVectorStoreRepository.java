package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.infrastructure.config.PgVectorConfigProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * pgvector 向量仓库实现（工单 0129，三期 Milvus→pgvector 替换）——RAG 文档块。
 *
 * <p>表结构：biz_chunks(id identity, content text, metadata jsonb, embedding halfvec(dim))；
 * 索引 hnsw(embedding halfvec_l2_ops) + gin(metadata jsonb_path_ops)。
 * 分数语义与 Milvus 时代严格对齐：<b>L2 距离，越小越好</b>（结果按距离升序=最优在前），
 * 下游 RRF 融合与排序消费方零漂移。
 *
 * <p>关键约束（issues/assets/pgvector-pg-migration-research.md）：
 * ①dim=2048 超 vector 索引上限 2000，列类型固定 halfvec；
 * ②查询距离表达式与索引列一致（列即 halfvec，参数 CAST(? AS halfvec)），否则索引失效；
 * ③租户过滤 metadata @> jsonb + GIN，库级开启 hnsw.iterative_scan 对抗后过滤少召回。
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true", matchIfMissing = true)
public class PgVectorVectorStoreRepository implements IVectorStoreRepository {

    private static final String SQL_INSERT =
            "INSERT INTO %s (embedding, content, metadata) VALUES (CAST(? AS halfvec), ?, CAST(? AS jsonb))";
    private static final String SQL_SEARCH_BASE =
            "SELECT content, metadata::text AS metadata, embedding <-> CAST(? AS halfvec) AS distance FROM %s %s"
                    + " ORDER BY embedding <-> CAST(? AS halfvec) LIMIT ?";
    private static final String SQL_DELETE = "DELETE FROM %s WHERE metadata @> CAST(? AS jsonb)";

    private final JdbcTemplate jdbcTemplate;
    private final PgVectorConfigProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 行映射：distance 即 L2 分数（越小越好），metadata 文本解析回 Map */
    private final RowMapper<VectorSearchResultVO> rowMapper = new RowMapper<>() {
        @Override
        public VectorSearchResultVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            VectorSearchResultVO vo = new VectorSearchResultVO();
            vo.setContent(rs.getString("content"));
            vo.setScore(rs.getFloat("distance"));
            vo.setMetadata(parseMetadata(rs.getString("metadata")));
            return vo;
        }
    };

    public PgVectorVectorStoreRepository(@Qualifier("vectorJdbc") JdbcTemplate vectorJdbc,
            PgVectorConfigProperties properties) {
        this.jdbcTemplate = vectorJdbc;
        this.properties = properties;
    }

    @Override
    public void ensureCollection() {
        String table = properties.getTable();
        jdbcTemplate.execute(String.format("""
                CREATE TABLE IF NOT EXISTS %s (
                  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                  content   TEXT   NOT NULL,
                  metadata  JSONB  NOT NULL DEFAULT '{}'::jsonb,
                  embedding halfvec(%d)
                )""", table, properties.getDimension()));
        jdbcTemplate.execute(String.format(
                "CREATE INDEX IF NOT EXISTS idx_%s_hnsw ON %s USING hnsw (embedding halfvec_l2_ops)"
                        + " WITH (m = %d, ef_construction = %d)",
                table, table, properties.getM(), properties.getEfConstruction()));
        jdbcTemplate.execute(String.format(
                "CREATE INDEX IF NOT EXISTS idx_%s_metadata ON %s USING gin (metadata jsonb_path_ops)",
                table, table));
        enableIterativeScan();
        log.info("[pgvector] 向量表就绪: {}（dim={}，HNSW L2，iterative_scan 开启）", table, properties.getDimension());
    }

    /**
     * 过滤+ANN 组合在 pgvector 为后过滤（会少召回），0.8.0+ 的 iterative scan 是官方解。
     * 连接池会话无法逐连接 SET，改库级默认（幂等；需库 owner，失败降级告警不阻断）。
     */
    private void enableIterativeScan() {
        try {
            String database = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
            jdbcTemplate.execute("ALTER DATABASE " + database + " SET hnsw.iterative_scan = strict_order");
            jdbcTemplate.execute("ALTER DATABASE " + database + " SET hnsw.ef_search = " + properties.getEfSearch());
        } catch (Exception e) {
            log.warn("[pgvector] 库级 iterative_scan 设置失败（需库 owner 权限），租户过滤召回可能缩水: {}",
                    e.getMessage());
        }
    }

    @Override
    public void insertChunks(List<DocumentChunkEntity> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String sql = String.format(SQL_INSERT, properties.getTable());
        List<Object[]> args = new ArrayList<>(chunks.size());
        for (DocumentChunkEntity chunk : chunks) {
            args.add(new Object[]{
                    toVectorLiteral(chunk.getVector()),
                    chunk.getContent(),
                    toJson(chunk.getMetadata())});
        }
        jdbcTemplate.batchUpdate(sql, args);
        log.debug("[pgvector] 写入 {} 个文档块", chunks.size());
    }

    @Override
    public List<VectorSearchResultVO> search(float[] queryVector, int topK) {
        return search(queryVector, topK, null);
    }

    @Override
    public List<VectorSearchResultVO> search(float[] queryVector, int topK, TenantScopeVO scope) {
        String q = toVectorLiteral(queryVector);
        String where = "";
        List<Object> args = new ArrayList<>();
        args.add(q);
        if (scope != null) {
            Map<String, Object> filter = new HashMap<>();
            filter.put("tenantId", scope.getTenantId());
            filter.put("ownerUserId", scope.getOwnerUserId());
            where = "WHERE metadata @> CAST(? AS jsonb)";
            args.add(toJson(filter));
        }
        args.add(q);
        args.add(topK);
        String sql = String.format(SQL_SEARCH_BASE, properties.getTable(), where);
        List<VectorSearchResultVO> results = jdbcTemplate.query(sql, rowMapper, args.toArray());
        log.debug("[pgvector] 检索 topK={} 命中 {} 条（scope={}）", topK, results.size(), scope != null);
        return results;
    }

    @Override
    public void deleteByDocumentId(String documentId, TenantScopeVO scope) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("documentId", documentId);
        if (scope != null) {
            filter.put("tenantId", scope.getTenantId());
            filter.put("ownerUserId", scope.getOwnerUserId());
        }
        int deleted = jdbcTemplate.update(String.format(SQL_DELETE, properties.getTable()), toJson(filter));
        log.info("[pgvector] 按文档删除向量: documentId={} 删除 {} 行", documentId, deleted);
    }

    @Override
    public boolean healthCheck() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables"
                            + " WHERE table_schema = current_schema() AND table_name = ?",
                    Integer.class, properties.getTable());
            return count != null && count > 0;
        } catch (Exception e) {
            log.error("[pgvector] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /** float[] → pgvector 文本字面量 '[0.1,0.2,...]'（经 CAST(? AS halfvec) 入库） */
    static String toVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("[pgvector] metadata 序列化失败，落空对象: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("[pgvector] metadata 解析失败: {}", json);
            return new HashMap<>();
        }
    }
}

package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.infrastructure.config.PgVectorConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * pgvector RAG 仓储单测（工单 0129）：SQL 形态（halfvec 转换/@> 过滤/LIMIT topK）、
 * L2 分数语义（distance→score 越小越好）、参数绑定与行映射。
 */
class PgVectorVectorStoreRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private PgVectorVectorStoreRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorConfigProperties properties = new PgVectorConfigProperties();
        repository = new PgVectorVectorStoreRepository(jdbcTemplate, properties);
    }

    @Test
    void vectorLiteralFormatting() {
        assertEquals("[0.1,0.25]", PgVectorVectorStoreRepository.toVectorLiteral(new float[]{0.1f, 0.25f}));
        assertEquals("[]", PgVectorVectorStoreRepository.toVectorLiteral(new float[0]));
        assertEquals("[]", PgVectorVectorStoreRepository.toVectorLiteral(null));
    }

    @Test
    void ensureCollectionCreatesTableAndIndexes() {
        repository.ensureCollection();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(5)).execute(sql.capture());
        String joined = String.join(";\n", sql.getAllValues());
        assertTrue(joined.contains("CREATE TABLE IF NOT EXISTS biz_chunks"), "应建 biz_chunks 表");
        assertTrue(joined.contains("halfvec(2048)"), "维度列应为 halfvec（2048 超 vector 索引上限）");
        assertTrue(joined.contains("USING hnsw (embedding halfvec_l2_ops)"), "L2 语义用 halfvec_l2_ops");
        assertTrue(joined.contains("USING gin (metadata jsonb_path_ops)"), "metadata 应建 GIN 索引");
        assertTrue(joined.contains("iterative_scan"), "应开启 iterative_scan 对抗后过滤少召回");
    }

    @Test
    void insertChunksBindsVectorLiteralAndJson() {
        DocumentChunkEntity chunk = new DocumentChunkEntity();
        chunk.setContent("文本块");
        chunk.setVector(new float[]{0.5f, 0.6f});
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "doc-1");
        chunk.setMetadata(metadata);

        repository.insertChunks(List.of(chunk));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(
                org.mockito.Mockito.contains("CAST(? AS halfvec)"), args.capture());
        Object[] row = args.getValue().get(0);
        assertEquals("[0.5,0.6]", row[0]);
        assertEquals("文本块", row[1]);
        assertTrue(((String) row[2]).contains("doc-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithScopeFiltersByJsonbContainment() {
        TenantScopeVO scope = new TenantScopeVO();
        scope.setTenantId("t1");
        scope.setOwnerUserId("u1");

        repository.search(new float[]{0.1f}, 5, scope);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(org.mockito.ArgumentMatchers.contains("<->"), any(RowMapper.class),
                args.capture());
        // 参数：查询向量、租户过滤 JSON、查询向量（ORDER BY 与 SELECT 同表达式）、topK
        assertEquals(4, args.getValue().length);
        assertTrue(((String) args.getValue()[1]).contains("\"tenantId\":\"t1\""));
        assertTrue(((String) args.getValue()[1]).contains("\"ownerUserId\":\"u1\""));
        assertEquals(5, args.getValue()[3]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithoutScopeHasThreeParams() {
        repository.search(new float[]{0.2f}, 3);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());
        assertEquals(3, args.getValue().length);
        assertEquals("[0.2]", args.getValue()[0]);
        assertEquals(3, args.getValue()[2]);
    }

    @Test
    void rowMapperMapsDistanceAsScore() throws Exception {
        // 通过 mock ResultSet 驱动行映射：distance=L2 距离直接作 score（越小越好语义保持）
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<VectorSearchResultVO> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("content")).thenReturn("块内容");
                    when(rs.getString("metadata")).thenReturn("{\"documentId\":\"d1\"}");
                    when(rs.getFloat("distance")).thenReturn(12.5f);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<VectorSearchResultVO> results = repository.search(new float[]{0.1f}, 1);

        assertEquals(12.5f, results.get(0).getScore());
        assertEquals("块内容", results.get(0).getContent());
        assertEquals("d1", results.get(0).getMetadata().get("documentId"));
    }

    @Test
    void deleteByDocumentIdBuildsContainmentFilter() {
        TenantScopeVO scope = new TenantScopeVO();
        scope.setTenantId("t9");
        scope.setOwnerUserId("u9");

        repository.deleteByDocumentId("doc-42", scope);

        verify(jdbcTemplate).update(org.mockito.Mockito.contains("metadata @> CAST(? AS jsonb)"),
                org.mockito.Mockito.contains("\"documentId\":\"doc-42\""));
    }

    @Test
    void healthCheckReflectsTableExistence() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        assertTrue(repository.healthCheck());

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        assertFalse(repository.healthCheck());
    }
}

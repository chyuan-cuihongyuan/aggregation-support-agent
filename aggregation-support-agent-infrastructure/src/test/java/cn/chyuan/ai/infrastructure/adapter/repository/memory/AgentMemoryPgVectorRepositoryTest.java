package cn.chyuan.ai.infrastructure.adapter.repository.memory;

import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryEntry;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.infrastructure.config.PgVectorConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 记忆 pgvector 仓储单测（工单 0130）：COSINE 相似度语义（[0,1] 越大越好）、
 * 0.7 阈值边界、scope 前缀过滤参数、向量直读解析、upsert/UPDATE SQL 形态。
 */
class AgentMemoryPgVectorRepositoryTest {

    private JdbcTemplate vectorJdbc;
    private IEmbeddingService embeddingService;
    private AgentMemoryPgVectorRepository repository;

    @BeforeEach
    void setUp() {
        vectorJdbc = mock(JdbcTemplate.class);
        embeddingService = mock(IEmbeddingService.class);
        repository = new AgentMemoryPgVectorRepository(vectorJdbc, new PgVectorConfigProperties());
        ReflectionTestUtils.setField(repository, "embeddingService", embeddingService);
        // @PostConstruct 不在单测触发（无真库）
    }

    @Test
    void literalRoundTrip() {
        float[] vector = {0.1f, -0.25f, 0.999f};
        assertEquals("[0.1,-0.25,0.999]", AgentMemoryPgVectorRepository.toLiteral(vector));
        assertArrayEquals(vector, AgentMemoryPgVectorRepository.parseLiteral("[0.1,-0.25,0.999]"), 1e-6f);
        assertArrayEquals(new float[0], AgentMemoryPgVectorRepository.parseLiteral("[]"));
        assertArrayEquals(new float[0], AgentMemoryPgVectorRepository.parseLiteral(null));
    }

    @Test
    void getEmbeddingReadsVectorColumnDirectly() {
        when(vectorJdbc.queryForList(anyString(), eq(String.class), eq("m1")))
                .thenReturn(List.of("[0.5,0.6]"));
        assertArrayEquals(new float[]{0.5f, 0.6f}, repository.getEmbedding("m1"), 1e-6f);
        // 畸形输入容错：空结果/解析异常均回退空数组而非抛出
        when(vectorJdbc.queryForList(anyString(), eq(String.class), eq("m2"))).thenReturn(List.of());
        assertEquals(0, repository.getEmbedding("m2").length);
        when(vectorJdbc.queryForList(anyString(), eq(String.class), eq("m3"))).thenReturn(List.of("not-a-vector"));
        assertEquals(0, repository.getEmbedding("m3").length);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchBindsScopePrefixAndSimilarity() {
        when(vectorJdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.search(new float[]{0.1f}, "t1", "u1", "/agent/s1", 7);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(vectorJdbc).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class), args.capture());
        assertTrue(sql.getValue().contains("<=>"), "COSINE 距离算子");
        assertTrue(sql.getValue().contains("GREATEST(0, 1 -"), "相似度 = 1 - 距离，[0,1] 语义");
        assertTrue(sql.getValue().contains("scope LIKE ?"), "scope 前缀过滤");
        assertTrue(sql.getValue().contains("ORDER BY embedding <=> CAST(? AS halfvec)"), "ORDER BY 用原始算子保索引匹配");
        // 参数：q, tenant, user, scope 前缀, q, limit
        assertEquals("/agent/s1%", args.getValue()[3]);
        assertEquals(7, args.getValue()[5]);
    }

    @Test
    void searchSimilarKeepsOnlyAboveThreshold() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});
        when(vectorJdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(
                        entityWithScore("m-high", 0.8),
                        entityWithScore("m-boundary", 0.7),
                        entityWithScore("m-low", 0.6)));

        List<MemoryEntry> entries = repository.searchSimilar("内容", "t", "u", null, 10);

        // > 0.7 严格阈值（与 Milvus 版口径一致）：0.8 保留，0.7 边界与 0.6 剔除
        assertEquals(1, entries.size());
        assertEquals("m-high", entries.get(0).getEntry().getMemoryId());
        assertEquals(0.8, entries.get(0).getScore(), 1e-9);
    }

    @Test
    void insertWithEmbeddingUpsertsOnMemoryId() {
        AgentMemoryEntity entity = AgentMemoryEntity.builder()
                .memoryId("m-1")
                .content("内容")
                .tenantId("t")
                .userId("u")
                .scope("/agent/a")
                .memoryType(MemoryType.FACT)
                .importance(0.5f)
                .contentHash("hash-1")
                .createdAt(Instant.ofEpochMilli(1000L))
                .build();

        repository.insertWithEmbedding(entity, new float[]{0.2f});

        verify(vectorJdbc).update(
                org.mockito.ArgumentMatchers.contains("ON CONFLICT (memory_id) DO UPDATE"),
                eq("m-1"), eq("[0.2]"), eq("内容"), eq("t"), eq("u"), eq(""), eq("/agent/a"),
                eq("FACT"), eq(0.5f), eq("hash-1"), eq(1000L));
    }

    @Test
    void updateEmbeddingUsesDirectUpdate() {
        repository.updateEmbedding("m-9", new float[]{0.3f});
        verify(vectorJdbc).update(
                org.mockito.ArgumentMatchers.contains("UPDATE agent_memory_vec SET embedding = CAST(? AS halfvec)"),
                eq("[0.3]"), eq("m-9"));
    }

    private AgentMemoryEntity entityWithScore(String memoryId, double score) {
        return AgentMemoryEntity.builder()
                .memoryId(memoryId)
                .content("内容-" + memoryId)
                .tenantId("t").userId("u")
                .scope("/agent/a")
                .memoryType(MemoryType.FACT)
                .importance(0.5f)
                .contentHash("hash-" + memoryId)
                .searchScore(score)
                .build();
    }
}

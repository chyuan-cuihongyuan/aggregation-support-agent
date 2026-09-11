package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.repository.IRagTraceRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.valobj.SearchOutcomeVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.cache.RetrievalCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索缓存编排挂点单测（工单 0167）
 * <p>
 * 覆盖验收：开关关闭零交互（零回归）/ 未命中回填缓存 / 命中跳过真实检索（缓存仅检索不生成）
 */
class EnhancedRagServiceCacheTest {

    private EnhancedRagService service;

    private IEmbeddingService embeddingService;

    private RetrievalCacheService cacheService;

    @BeforeEach
    void setUp() {
        service = new EnhancedRagService();
        ReflectionTestUtils.setField(service, "vectorTopK", 5);
        ReflectionTestUtils.setField(service, "bm25TopK", 5);
        ReflectionTestUtils.setField(service, "retrievalTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "knowledgeGraphEnabled", false);
        ReflectionTestUtils.setField(service, "hybridEnabled", false);
        ReflectionTestUtils.setField(service, "rerankEnabled", false);
        ReflectionTestUtils.setField(service, "rerankProvider", "none");
        ReflectionTestUtils.setField(service, "reorderEnabled", false);
        ReflectionTestUtils.setField(service, "queryRewriteEnabled", false);
        ReflectionTestUtils.setField(service, "parentChildEnabled", false);
        ReflectionTestUtils.setField(service, "cacheEnabled", false);

        embeddingService = mock(IEmbeddingService.class);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1.0f});
        IVectorStoreRepository vectorStore = mock(IVectorStoreRepository.class);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "d1");
        when(vectorStore.search(any(float[].class), anyInt(), any())).thenReturn(List.of(
                VectorSearchResultVO.builder().content("真实检索块").score(0.5f).metadata(metadata).build()));
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "vectorStoreRepository", vectorStore);
        ReflectionTestUtils.setField(service, "ragTraceRepository", mock(IRagTraceRepository.class));

        cacheService = mock(RetrievalCacheService.class);
        ReflectionTestUtils.setField(service, "retrievalCacheService", cacheService);
    }

    @Test
    void cacheDisabledNeverTouchesCache() {
        // 开关关（默认）：缓存服务零交互（零回归）
        SearchOutcomeVO outcome = service.searchWithTrace("query", 5, TenantScopeVO.singleUser("u1"));

        assertThat(outcome.getRawResults()).extracting(VectorSearchResultVO::getContent)
                .containsExactly("真实检索块");
        verify(cacheService, never()).get(anyString());
        verify(cacheService, never()).put(anyString(), any());
    }

    @Test
    void cacheMissFallsThroughAndBackfills() {
        // 未命中：真实检索后回填缓存
        ReflectionTestUtils.setField(service, "cacheEnabled", true);
        when(cacheService.get("query")).thenReturn(Optional.empty());

        SearchOutcomeVO outcome = service.searchWithTrace("query", 5, TenantScopeVO.singleUser("u1"));

        verify(embeddingService).embed("query");
        verify(cacheService).put(anyString(), any());
        assertThat(outcome.getRawResults()).extracting(VectorSearchResultVO::getContent)
                .containsExactly("真实检索块");
    }

    @Test
    void cacheHitSkipsRealRetrieval() {
        // 命中：直接返回缓存 chunks+分数，跳过 embedding 与向量检索（仅检索不生成）
        ReflectionTestUtils.setField(service, "cacheEnabled", true);
        VectorSearchResultVO cachedChunk = VectorSearchResultVO.builder()
                .content("缓存命中块").score(0.88f)
                .metadata(new HashMap<>(Map.of("documentId", "d1")))
                .build();
        when(cacheService.get("query")).thenReturn(Optional.of(List.of(cachedChunk)));

        SearchOutcomeVO outcome = service.searchWithTrace("query", 5, TenantScopeVO.singleUser("u1"));

        verify(embeddingService, never()).embed(anyString());
        verify(cacheService, never()).put(anyString(), any());
        assertThat(outcome.getRawResults()).extracting(VectorSearchResultVO::getContent)
                .containsExactly("缓存命中块");
        assertThat(outcome.getRawResults().get(0).getScore()).isEqualTo(0.88f);
    }
}

package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.fusion.IResultFusionService;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnhancedRagServiceRetrievalTest {

    @Test
    void bm25StillReturnsWhenVectorFails() {
        EnhancedRagService service = serviceWithFusion();
        IEmbeddingService embeddingService = mock(IEmbeddingService.class);
        when(embeddingService.embed("query")).thenThrow(new RuntimeException("vector down"));
        IBM25SearchService bm25 = mock(IBM25SearchService.class);
        when(bm25.search(eq("query"), anyInt(), any())).thenReturn(List.of(result("bm25")));

        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "bm25SearchService", bm25);
        ReflectionTestUtils.setField(service, "bm25Enabled", true);

        List<VectorSearchResultVO> results = ReflectionTestUtils.invokeMethod(
                service, "multiPathRetrieval", "query", 5, TenantScopeVO.singleUser("u1"));

        assertThat(results).extracting(VectorSearchResultVO::getContent).containsExactly("bm25");
    }

    @Test
    void vectorStillReturnsWhenBm25Fails() {
        EnhancedRagService service = serviceWithFusion();
        IEmbeddingService embeddingService = mock(IEmbeddingService.class);
        when(embeddingService.embed("query")).thenReturn(new float[]{1.0f});
        IVectorStoreRepository vectorStore = mock(IVectorStoreRepository.class);
        when(vectorStore.search(any(float[].class), anyInt(), any())).thenReturn(List.of(result("vector")));
        IBM25SearchService bm25 = mock(IBM25SearchService.class);
        when(bm25.search(eq("query"), anyInt(), any())).thenThrow(new RuntimeException("bm25 down"));

        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "vectorStoreRepository", vectorStore);
        ReflectionTestUtils.setField(service, "bm25SearchService", bm25);
        ReflectionTestUtils.setField(service, "bm25Enabled", true);

        List<VectorSearchResultVO> results = ReflectionTestUtils.invokeMethod(
                service, "multiPathRetrieval", "query", 5, TenantScopeVO.singleUser("u1"));

        assertThat(results).extracting(VectorSearchResultVO::getContent).containsExactly("vector");
    }

    private EnhancedRagService serviceWithFusion() {
        EnhancedRagService service = new EnhancedRagService();
        ReflectionTestUtils.setField(service, "vectorTopK", 5);
        ReflectionTestUtils.setField(service, "bm25TopK", 5);
        ReflectionTestUtils.setField(service, "retrievalTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "knowledgeGraphEnabled", false);
        IResultFusionService fusion = mock(IResultFusionService.class);
        when(fusion.rrfFusion(any(), anyInt())).thenAnswer(invocation -> {
            List<List<VectorSearchResultVO>> groups = invocation.getArgument(0);
            List<VectorSearchResultVO> merged = new ArrayList<>();
            groups.forEach(merged::addAll);
            return merged;
        });
        ReflectionTestUtils.setField(service, "resultFusionService", fusion);
        return service;
    }

    private VectorSearchResultVO result(String content) {
        return VectorSearchResultVO.builder()
                .content(content)
                .score(1.0f)
                .metadata(new java.util.HashMap<>(Map.of("source", "test")))
                .build();
    }
}

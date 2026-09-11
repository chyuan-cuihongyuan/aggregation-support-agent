package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.port.IKeywordSearchPort;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.fusion.IResultFusionService;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 混合检索编排开关单测（工单 0163）
 * <p>
 * 覆盖验收：
 * <ul>
 *   <li>编排开关单测：关（默认）= 单路原样、关键词端口零交互（零回归）</li>
 *   <li>关键词路查询端口 mock 合同：开启时端口被调用且两路 RRF 融合、端口异常降级不阻断</li>
 * </ul>
 */
class EnhancedRagServiceHybridTest {

    @Test
    void hybridDisabledKeepsSingleVectorPathAndNeverTouchesKeywordPort() {
        EnhancedRagService service = baseService();
        stubVectorPath(service, List.of(result("vector-only", "d1", 0)));
        IKeywordSearchPort keywordPort = mock(IKeywordSearchPort.class);
        when(keywordPort.isAvailable()).thenReturn(true);
        ReflectionTestUtils.setField(service, "keywordSearchPort", keywordPort);
        // 开关默认关
        ReflectionTestUtils.setField(service, "hybridEnabled", false);

        List<VectorSearchResultVO> results = ReflectionTestUtils.invokeMethod(
                service, "multiPathRetrieval", "query", 5, TenantScopeVO.singleUser("u1"));

        // 单路=原行为：结果原样
        assertThat(results).extracting(VectorSearchResultVO::getContent).containsExactly("vector-only");
        // 关键词端口零交互（零回归断言）
        verify(keywordPort, never()).search(anyString(), anyInt(), any());
    }

    @Test
    void hybridDisabledFallsBackToLegacyFusionWhenBm25Enabled() {
        EnhancedRagService service = baseService();
        stubVectorPath(service, List.of(result("vector", "d1", 0)));
        IBM25SearchService bm25 = mock(IBM25SearchService.class);
        when(bm25.search(eq("query"), anyInt(), any())).thenReturn(List.of(result("bm25", "d1", 1)));
        ReflectionTestUtils.setField(service, "bm25SearchService", bm25);
        ReflectionTestUtils.setField(service, "bm25Enabled", true);
        ReflectionTestUtils.setField(service, "hybridEnabled", false);

        List<VectorSearchResultVO> results = ReflectionTestUtils.invokeMethod(
                service, "multiPathRetrieval", "query", 5, TenantScopeVO.singleUser("u1"));

        // 关（默认）时沿用既有 resultFusionService 融合链路
        assertThat(results).extracting(VectorSearchResultVO::getContent).containsExactly("vector", "bm25");
        verify(legacyFusion(service)).rrfFusion(any(), anyInt());
    }

    @Test
    void hybridEnabledFusesVectorAndKeywordPaths() {
        EnhancedRagService service = baseService();
        stubVectorPath(service, List.of(result("vec-head", "d1", 0), result("vec-tail", "d1", 1)));
        IKeywordSearchPort keywordPort = mock(IKeywordSearchPort.class);
        when(keywordPort.isAvailable()).thenReturn(true);
        when(keywordPort.search(eq("query"), anyInt(), any()))
                .thenReturn(List.of(result("keyword-head", "d1", 2), result("vec-head", "d1", 0)));
        ReflectionTestUtils.setField(service, "keywordSearchPort", keywordPort);
        ReflectionTestUtils.setField(service, "hybridEnabled", true);
        ReflectionTestUtils.setField(service, "hybridRrfK", 60);

        List<VectorSearchResultVO> results = ReflectionTestUtils.invokeMethod(
                service, "multiPathRetrieval", "query", 5, TenantScopeVO.singleUser("u1"));

        // 端口 mock 合同：关键词路被调用并参与融合
        verify(keywordPort).search(eq("query"), anyInt(), any());
        // 两路融合：重叠块"vec-head"在两路分别第 1、第 2 名，RRF 累积后应居首
        assertThat(results).extracting(VectorSearchResultVO::getContent).contains("vec-head");
        assertThat(results.get(0).getContent()).isEqualTo("vec-head");
        // RRF 分数 = 1/61 + 1/62
        assertThat(results.get(0).getScore()).isCloseTo((float) (1.0d / 61 + 1.0d / 62), within(0.0001f));
        assertThat(results).hasSize(3);
    }

    @Test
    void hybridEnabledDegradesToVectorOnlyWhenKeywordPortThrows() {
        EnhancedRagService service = baseService();
        stubVectorPath(service, List.of(result("vector", "d1", 0)));
        IKeywordSearchPort keywordPort = mock(IKeywordSearchPort.class);
        when(keywordPort.isAvailable()).thenReturn(true);
        when(keywordPort.search(anyString(), anyInt(), any())).thenThrow(new RuntimeException("es down"));
        ReflectionTestUtils.setField(service, "keywordSearchPort", keywordPort);
        ReflectionTestUtils.setField(service, "hybridEnabled", true);

        List<VectorSearchResultVO> results = ReflectionTestUtils.invokeMethod(
                service, "multiPathRetrieval", "query", 5, TenantScopeVO.singleUser("u1"));

        // 端口异常不阻断主链路：单向量路原样返回
        assertThat(results).extracting(VectorSearchResultVO::getContent).containsExactly("vector");
    }

    @Test
    void hybridEnabledSkipsKeywordPathWhenPortUnavailable() {
        EnhancedRagService service = baseService();
        stubVectorPath(service, List.of(result("vector", "d1", 0)));
        IKeywordSearchPort keywordPort = mock(IKeywordSearchPort.class);
        when(keywordPort.isAvailable()).thenReturn(false);
        ReflectionTestUtils.setField(service, "keywordSearchPort", keywordPort);
        ReflectionTestUtils.setField(service, "hybridEnabled", true);

        List<VectorSearchResultVO> results = ReflectionTestUtils.invokeMethod(
                service, "multiPathRetrieval", "query", 5, TenantScopeVO.singleUser("u1"));

        assertThat(results).extracting(VectorSearchResultVO::getContent).containsExactly("vector");
        verify(keywordPort, never()).search(anyString(), anyInt(), any());
    }

    /** 构造最小可用的 EnhancedRagService（关闭图谱，既有融合服务为 spy-able mock） */
    private EnhancedRagService baseService() {
        EnhancedRagService service = new EnhancedRagService();
        ReflectionTestUtils.setField(service, "vectorTopK", 5);
        ReflectionTestUtils.setField(service, "bm25TopK", 5);
        ReflectionTestUtils.setField(service, "retrievalTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "knowledgeGraphEnabled", false);
        ReflectionTestUtils.setField(service, "hybridEnabled", false);
        ReflectionTestUtils.setField(service, "hybridRrfK", 60);
        ReflectionTestUtils.setField(service, "resultFusionService", legacyFusionMock());
        return service;
    }

    /** 既有融合服务 mock：默认把各路结果顺序拼接，模拟 RRF 融合后的列表 */
    private IResultFusionService legacyFusionMock() {
        IResultFusionService fusion = Mockito.mock(IResultFusionService.class);
        when(fusion.rrfFusion(any(), anyInt())).thenAnswer(invocation -> {
            List<List<VectorSearchResultVO>> groups = invocation.getArgument(0);
            List<VectorSearchResultVO> merged = new ArrayList<>();
            groups.forEach(merged::addAll);
            return merged;
        });
        return fusion;
    }

    /** 既有融合服务 mock 的句柄（用于 verify 既有链路仍被走到） */
    private IResultFusionService legacyFusion(EnhancedRagService service) {
        return (IResultFusionService) ReflectionTestUtils.getField(service, "resultFusionService");
    }

    private void stubVectorPath(EnhancedRagService service, List<VectorSearchResultVO> results) {
        IEmbeddingService embeddingService = mock(IEmbeddingService.class);
        when(embeddingService.embed("query")).thenReturn(new float[]{1.0f});
        IVectorStoreRepository vectorStore = mock(IVectorStoreRepository.class);
        when(vectorStore.search(any(float[].class), anyInt(), any())).thenReturn(results);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "vectorStoreRepository", vectorStore);
    }

    private VectorSearchResultVO result(String content, String documentId, int chunkIndex) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("chunkIndex", chunkIndex);
        return VectorSearchResultVO.builder()
                .content(content)
                .score(1.0f)
                .metadata(metadata)
                .build();
    }
}

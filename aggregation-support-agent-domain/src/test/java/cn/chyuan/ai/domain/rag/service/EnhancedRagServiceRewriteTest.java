package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.port.IQueryRewritePort;
import cn.chyuan.ai.domain.rag.adapter.repository.IRagTraceRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.RagTraceEntity;
import cn.chyuan.ai.domain.rag.model.valobj.SearchOutcomeVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 查询改写端口编排单测（工单 0165）
 * <p>
 * 覆盖验收：开关装配（端口缺席走既有路径零回归）与日志双写断言
 * （改写前后 query 都进检索日志：rag_trace.query_text / rewrite_text 双写）。
 */
class EnhancedRagServiceRewriteTest {

    private EnhancedRagService service;

    private IEmbeddingService embeddingService;

    private IRagTraceRepository ragTraceRepository;

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

        embeddingService = mock(IEmbeddingService.class);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1.0f});
        IVectorStoreRepository vectorStore = mock(IVectorStoreRepository.class);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "d1");
        when(vectorStore.search(any(float[].class), anyInt(), any())).thenReturn(List.of(
                VectorSearchResultVO.builder().content("命中块").score(0.5f).metadata(metadata).build()));
        ragTraceRepository = mock(IRagTraceRepository.class);

        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "vectorStoreRepository", vectorStore);
        ReflectionTestUtils.setField(service, "ragTraceRepository", ragTraceRepository);
    }

    @Test
    void rewritePortRewritesQueryAndWritesBothToTrace() {
        IQueryRewritePort rewritePort = mock(IQueryRewritePort.class);
        when(rewritePort.rewrite("原始查询")).thenReturn("改写查询");
        ReflectionTestUtils.setField(service, "queryRewritePort", rewritePort);

        SearchOutcomeVO outcome = service.searchWithTrace("原始查询", 5, TenantScopeVO.singleUser("u1"));

        // 检索用改写后的 query
        verify(embeddingService).embed("改写查询");
        // 日志双写：trace.query_text=原 query，rewrite_text=改写 query
        ArgumentCaptor<RagTraceEntity> captor = ArgumentCaptor.forClass(RagTraceEntity.class);
        verify(ragTraceRepository).save(captor.capture());
        assertThat(captor.getValue().getQueryText()).isEqualTo("原始查询");
        assertThat(captor.getValue().getRewriteText()).isEqualTo("改写查询");
        // 出参同时携带改写前后 query
        assertThat(outcome.getOriginalQuery()).isEqualTo("原始查询");
        assertThat(outcome.getRewriteQuery()).isEqualTo("改写查询");
    }

    @Test
    void absentRewritePortKeepsLegacySinglePath() {
        // 开关关（端口未装配）：query 原样直通，rewrite_text 为空（零回归）
        SearchOutcomeVO outcome = service.searchWithTrace("原始查询", 5, TenantScopeVO.singleUser("u1"));

        verify(embeddingService).embed("原始查询");
        ArgumentCaptor<RagTraceEntity> captor = ArgumentCaptor.forClass(RagTraceEntity.class);
        verify(ragTraceRepository).save(captor.capture());
        assertThat(captor.getValue().getRewriteText()).isNull();
        assertThat(outcome.getRewriteQuery()).isNull();
    }

    @Test
    void rewritePortFailureFallsBackToOriginalQuery() {
        // 端口异常：原 query 直通检索，不抛出
        IQueryRewritePort rewritePort = mock(IQueryRewritePort.class);
        when(rewritePort.rewrite(anyString())).thenThrow(new RuntimeException("rewrite down"));
        ReflectionTestUtils.setField(service, "queryRewritePort", rewritePort);

        SearchOutcomeVO outcome = service.searchWithTrace("原始查询", 5, TenantScopeVO.singleUser("u1"));

        verify(embeddingService).embed("原始查询");
        ArgumentCaptor<RagTraceEntity> captor = ArgumentCaptor.forClass(RagTraceEntity.class);
        verify(ragTraceRepository).save(captor.capture());
        assertThat(captor.getValue().getRewriteText()).isNull();
        assertThat(outcome.getRewriteQuery()).isNull();
    }

    @Test
    void rewritePortBlankOutputKeepsOriginalQuery() {
        // 端口空产出兜底：原 query 直通
        IQueryRewritePort rewritePort = mock(IQueryRewritePort.class);
        when(rewritePort.rewrite("原始查询")).thenReturn("  ");
        ReflectionTestUtils.setField(service, "queryRewritePort", rewritePort);

        service.searchWithTrace("原始查询", 5, TenantScopeVO.singleUser("u1"));

        verify(embeddingService).embed("原始查询");
    }
}

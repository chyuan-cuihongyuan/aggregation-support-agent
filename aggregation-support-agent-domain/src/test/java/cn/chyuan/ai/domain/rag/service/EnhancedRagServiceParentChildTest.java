package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.repository.IRagTraceRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.RagTraceEntity;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.chunker.ParentAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
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
 * 父子分块装配编排单测（工单 0166）
 * <p>
 * 覆盖验收：开关关闭零回归（命中子块原样不去重）+ 开启时去重回取父块 + 检索日志增列记录
 */
class EnhancedRagServiceParentChildTest {

    private EnhancedRagService service;

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
        ReflectionTestUtils.setField(service, "parentMaxChars", 1000);

        IEmbeddingService embeddingService = mock(IEmbeddingService.class);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1.0f});
        ragTraceRepository = mock(IRagTraceRepository.class);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "vectorStoreRepository", vectorStoreWithTwoChildrenSameParent());
        ReflectionTestUtils.setField(service, "ragTraceRepository", ragTraceRepository);
    }

    @Test
    void parentChildDisabledKeepsChildChunksUntouched() {
        // 开关关（默认）：即使命中带父块元数据也不装配（子块原样、不去重=零回归）
        ReflectionTestUtils.setField(service, "parentChildEnabled", false);

        List<VectorSearchResultVO> results = invokeDoSearchInternal();

        assertThat(results).extracting(VectorSearchResultVO::getContent).containsExactly("子块A", "子块B");
        assertThat(results.get(0).getMetadata()).doesNotContainKey(ParentAssembler.META_PARENT_ASSEMBLED);
    }

    @Test
    void parentChildEnabledAssemblesAndDeduplicatesParent() {
        // 开关开：同父块两子块去重为一条父块记录，content 替换为父块全文
        ReflectionTestUtils.setField(service, "parentChildEnabled", true);

        List<VectorSearchResultVO> results = invokeDoSearchInternal();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("父块全文内容");
        assertThat(results.get(0).getScore()).isEqualTo(0.9f);
        assertThat(results.get(0).getMetadata().get(ParentAssembler.META_PARENT_ASSEMBLED)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void searchWithTraceWritesParentColumnsWhenEnabled() {
        // 检索日志增列：开启时 parent_ids / parent_texts 记录去重后父块
        ReflectionTestUtils.setField(service, "parentChildEnabled", true);

        service.searchWithTrace("query", 5, TenantScopeVO.singleUser("u1"));

        ArgumentCaptor<RagTraceEntity> captor = ArgumentCaptor.forClass(RagTraceEntity.class);
        verify(ragTraceRepository).save(captor.capture());
        assertThat(captor.getValue().getParentIds()).isEqualTo("[\"p1\"]");
        assertThat(captor.getValue().getParentTexts()).isEqualTo("[\"父块全文内容\"]");
    }

    @Test
    void searchWithTraceLeavesParentColumnsNullWhenDisabled() {
        // 开关关：增列为 null（存量兼容）
        ReflectionTestUtils.setField(service, "parentChildEnabled", false);

        service.searchWithTrace("query", 5, TenantScopeVO.singleUser("u1"));

        ArgumentCaptor<RagTraceEntity> captor = ArgumentCaptor.forClass(RagTraceEntity.class);
        verify(ragTraceRepository).save(captor.capture());
        assertThat(captor.getValue().getParentIds()).isNull();
        assertThat(captor.getValue().getParentTexts()).isNull();
    }

    /** 调用内部检索流程并提取 results（doSearchInternal 返回 InternalSearchOutput） */
    @SuppressWarnings("unchecked")
    private List<VectorSearchResultVO> invokeDoSearchInternal() {
        Object output = ReflectionTestUtils.invokeMethod(
                service, "doSearchInternal", "query", 5, TenantScopeVO.singleUser("u1"));
        return (List<VectorSearchResultVO>) ReflectionTestUtils.getField(output, "results");
    }

    private IVectorStoreRepository vectorStoreWithTwoChildrenSameParent() {
        IVectorStoreRepository vectorStore = mock(IVectorStoreRepository.class);
        when(vectorStore.search(any(float[].class), anyInt(), any()))
                .thenAnswer(invocation -> new ArrayList<>(List.of(child("子块A", 0.9f), child("子块B", 0.8f))));
        return vectorStore;
    }

    private VectorSearchResultVO child(String content, float score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "d1");
        metadata.put(ParentAssembler.META_PARENT_ID, "p1");
        metadata.put(ParentAssembler.META_PARENT_TEXT, "父块全文内容");
        return VectorSearchResultVO.builder()
                .content(content)
                .score(score)
                .metadata(metadata)
                .build();
    }
}

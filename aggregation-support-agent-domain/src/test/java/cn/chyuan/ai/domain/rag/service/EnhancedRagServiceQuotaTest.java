package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.knowledgebase.QuotaExceededException;
import cn.chyuan.ai.domain.knowledgebase.service.KnowledgeQuotaService;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParserFactory;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import cn.chyuan.ai.domain.rag.service.chunker.SemanticChunker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户知识库配额两处校验挂点单测（工单 0168）
 * <p>
 * 覆盖验收：文档入库前校验挂点 / 分块入库前校验挂点 / 服务缺席兼容（零回归）
 */
class EnhancedRagServiceQuotaTest {

    private EnhancedRagService service;

    private KnowledgeQuotaService quotaService;

    private IDocumentMetadataRepository documentMetadataRepository;

    private IVectorStoreRepository vectorStoreRepository;

    @BeforeEach
    void setUp() {
        service = new EnhancedRagService();
        ReflectionTestUtils.setField(service, "bm25Enabled", false);
        ReflectionTestUtils.setField(service, "knowledgeGraphEnabled", false);
        ReflectionTestUtils.setField(service, "parentChildEnabled", false);
        ReflectionTestUtils.setField(service, "cacheEnabled", false);

        quotaService = mock(KnowledgeQuotaService.class);
        documentMetadataRepository = mock(IDocumentMetadataRepository.class);
        vectorStoreRepository = mock(IVectorStoreRepository.class);
        IEmbeddingService embeddingService = mock(IEmbeddingService.class);
        when(embeddingService.embedBatch(any())).thenReturn(List.of(new float[]{1.0f}, new float[]{2.0f}));
        IDocumentParserFactory parserFactory = mock(IDocumentParserFactory.class);
        ParsedDocumentVO parsed = mock(ParsedDocumentVO.class);
        when(parsed.getTextContent()).thenReturn("文档内容");
        when(parsed.getSections()).thenReturn(java.util.Collections.emptyList());
        when(parserFactory.parse(any(), anyString(), any())).thenReturn(parsed);
        SemanticChunker semanticChunker = mock(SemanticChunker.class);
        when(semanticChunker.chunk(any(), anyString())).thenReturn(List.of(
                DocumentChunkEntity.builder().id("c1").content("块一").metadata(new HashMap<>()).build(),
                DocumentChunkEntity.builder().id("c2").content("块二").metadata(new HashMap<>()).build()));

        ReflectionTestUtils.setField(service, "knowledgeQuotaService", quotaService);
        ReflectionTestUtils.setField(service, "documentMetadataRepository", documentMetadataRepository);
        ReflectionTestUtils.setField(service, "vectorStoreRepository", vectorStoreRepository);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "documentParserFactory", parserFactory);
        ReflectionTestUtils.setField(service, "semanticChunker", semanticChunker);
    }

    @Test
    void absentQuotaServiceAllowsUpload() {
        // 服务缺席（未装配）：不校验直接上传（兼容/零回归）
        ReflectionTestUtils.setField(service, "knowledgeQuotaService", null);

        assertThatCode(() -> service.uploadDocument(command())).doesNotThrowAnyException();
        verify(documentMetadataRepository).save(any());
    }

    @Test
    void documentQuotaExceededRejectsBeforePersistence() {
        // 挂点一：文档配额超限 → 元数据落库前即拒绝
        doThrow(new QuotaExceededException("文档数量超出配额"))
                .when(quotaService).checkDocumentQuota("t1");

        assertThatThrownBy(() -> service.uploadDocument(command()))
                .isInstanceOf(QuotaExceededException.class);
        verify(documentMetadataRepository, never()).save(any());
    }

    @Test
    void chunkQuotaExceededRejectsBeforeVectorInsert() {
        // 挂点二：分块配额超限 → 向量入库前拒绝
        doThrow(new QuotaExceededException("分块数量超出配额"))
                .when(quotaService).checkChunkQuota(eq("t1"), anyInt());

        assertThatThrownBy(() -> service.uploadDocument(command()))
                .isInstanceOf(QuotaExceededException.class);
        verify(vectorStoreRepository, never()).insertChunks(any());
    }

    @Test
    void normalUploadInvokesBothCheckpoints() {
        // 正常上传：两处挂点均被调用（文档数 + 分块数=2）
        assertThatCode(() -> service.uploadDocument(command())).doesNotThrowAnyException();
        verify(quotaService).checkDocumentQuota("t1");
        verify(quotaService).checkChunkQuota("t1", 2);
        verify(vectorStoreRepository).insertChunks(any());
    }

    private DocumentUploadCommand command() {
        return DocumentUploadCommand.builder()
                .documentId("doc-1")
                .fileName("test.md")
                .mimeType("text/markdown")
                .content("# 标题\n内容")
                .tenantId("t1")
                .userId("u1")
                .build();
    }
}

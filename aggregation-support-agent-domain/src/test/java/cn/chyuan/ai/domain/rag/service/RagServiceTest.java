package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParserFactory;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IRagTraceRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.domain.rag.model.valobj.*;
import cn.chyuan.ai.domain.rag.service.chunker.SemanticChunker;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import cn.chyuan.ai.domain.rag.service.retrieval.IHybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private IEmbeddingService embeddingService;

    @Mock
    private IVectorStoreRepository vectorStoreRepository;

    @Mock
    private IDocumentParserFactory documentParserFactory;

    @Mock
    private SemanticChunker semanticChunker;

    @Mock
    private IDocumentMetadataRepository documentMetadataRepository;

    @Mock
    private IHybridSearchService hybridSearchService;

    @Mock
    private IBM25SearchService bm25SearchService;

    @Mock
    private IRagTraceRepository ragTraceRepository;

    @InjectMocks
    private RagService ragService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ragService, "defaultTopK", 5);
        ReflectionTestUtils.setField(ragService, "chunkMaxSize", 1000);
        ReflectionTestUtils.setField(ragService, "chunkOverlap", 100);
        ReflectionTestUtils.setField(ragService, "hybridSearchService", hybridSearchService);
        ReflectionTestUtils.setField(ragService, "bm25SearchService", bm25SearchService);
    }

    @Test
    void testUploadDocument_Success() {
        // Given
        DocumentUploadCommand command = DocumentUploadCommand.builder()
                .documentId("doc-123")
                .fileName("test.pdf")
                .mimeType("application/pdf")
                .userId("user-1")
                .tenantId("tenant-1")
                .content("Test document content")
                .build();

        ParsedDocumentVO parsedDoc = ParsedDocumentVO.builder()
                .textContent("Test document content")
                .sections(new ArrayList<>())
                .build();

        DocumentChunkEntity chunk = DocumentChunkEntity.builder()
                .id("chunk-1")
                .content("Test content")
                .metadata(new HashMap<>())
                .build();

        float[] vector = new float[]{0.1f, 0.2f, 0.3f};

        when(documentParserFactory.parse(any(byte[].class), anyString(), anyString()))
                .thenReturn(parsedDoc);
        when(semanticChunker.chunk(any(ParsedDocumentVO.class), anyString()))
                .thenReturn(Arrays.asList(chunk));
        when(embeddingService.embedBatch(anyList()))
                .thenReturn(Arrays.asList(vector));

        // When
        ragService.uploadDocument(command);

        // Then
        verify(documentMetadataRepository).save(any(DocumentMetadataEntity.class));
        verify(documentParserFactory).parse(any(byte[].class), eq("test.pdf"), eq("application/pdf"));
        verify(semanticChunker).chunk(any(ParsedDocumentVO.class), eq("test.pdf"));
        verify(embeddingService).embedBatch(anyList());
        verify(vectorStoreRepository).insertChunks(anyList());
        verify(documentMetadataRepository).updateStatus(eq("doc-123"), eq("success"), eq(1), anyInt(), anyInt(), eq(""));
    }

    @Test
    void testUploadDocument_EmptyChunks() {
        // Given
        DocumentUploadCommand command = DocumentUploadCommand.builder()
                .documentId("doc-123")
                .fileName("empty.pdf")
                .userId("user-1")
                .content("Test content")
                .build();

        ParsedDocumentVO parsedDoc = ParsedDocumentVO.builder()
                .textContent("")
                .sections(new ArrayList<>())
                .build();

        when(documentParserFactory.parse(any(byte[].class), anyString(), anyString()))
                .thenReturn(parsedDoc);
        when(semanticChunker.chunk(any(ParsedDocumentVO.class), anyString()))
                .thenReturn(Collections.emptyList());

        // When
        ragService.uploadDocument(command);

        // Then
        verify(documentMetadataRepository).updateStatus(eq("doc-123"), eq("success"), eq(0), eq(0), eq(0), eq("文档分块结果为空"));
        verify(vectorStoreRepository, never()).insertChunks(anyList());
    }

    @Test
    void testUploadDocument_WithBM25Index() {
        // Given
        DocumentUploadCommand command = DocumentUploadCommand.builder()
                .documentId("doc-123")
                .fileName("test.pdf")
                .userId("user-1")
                .content("Test content")
                .build();

        ParsedDocumentVO parsedDoc = ParsedDocumentVO.builder()
                .textContent("Test content")
                .sections(new ArrayList<>())
                .build();

        DocumentChunkEntity chunk = DocumentChunkEntity.builder()
                .id("chunk-1")
                .content("Test content")
                .metadata(new HashMap<>())
                .build();

        float[] vector = new float[]{0.1f, 0.2f};

        when(documentParserFactory.parse(any(byte[].class), anyString(), anyString()))
                .thenReturn(parsedDoc);
        when(semanticChunker.chunk(any(), anyString()))
                .thenReturn(Arrays.asList(chunk));
        when(embeddingService.embedBatch(anyList()))
                .thenReturn(Arrays.asList(vector));
        when(bm25SearchService.isAvailable()).thenReturn(true);

        // When
        ragService.uploadDocument(command);

        // Then
        verify(bm25SearchService).addDocuments(anyMap(), anyMap());
    }

    @Test
    void testSearch_Success() {
        // Given
        String query = "test query";
        int topK = 5;
        TenantScopeVO scope = TenantScopeVO.builder()
                .tenantId("tenant-1")
                .ownerUserId("user-1")
                .build();

        float[] queryVector = new float[]{0.1f, 0.2f, 0.3f};
        VectorSearchResultVO result = VectorSearchResultVO.builder()
                .content("Test content")
                .score(0.95f)
                .metadata(Map.of("documentId", "doc-1", "_source", "test.pdf"))
                .build();

        when(embeddingService.embed(eq(query))).thenReturn(queryVector);
        when(vectorStoreRepository.search(eq(queryVector), eq(topK), eq(scope)))
                .thenReturn(Arrays.asList(result));

        // When
        List<VectorSearchResultVO> results = ragService.search(query, topK, scope);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("Test content", results.get(0).getContent());
        verify(embeddingService).embed(eq(query));
        verify(vectorStoreRepository).search(eq(queryVector), eq(topK), eq(scope));
    }

    @Test
    void testSearchWithTrace_Success() {
        // Given
        String query = "test query";
        int topK = 5;
        TenantScopeVO scope = TenantScopeVO.builder()
                .tenantId("tenant-1")
                .ownerUserId("user-1")
                .build();

        float[] queryVector = new float[]{0.1f, 0.2f};
        VectorSearchResultVO result = VectorSearchResultVO.builder()
                .content("Test content")
                .score(0.95f)
                .metadata(Map.of("documentId", "doc-1", "chunkId", "chunk-1"))
                .build();

        when(embeddingService.embed(eq(query))).thenReturn(queryVector);
        when(vectorStoreRepository.search(eq(queryVector), eq(topK), eq(scope)))
                .thenReturn(Arrays.asList(result));

        // When
        SearchOutcomeVO outcome = ragService.searchWithTrace(query, topK, scope);

        // Then
        assertNotNull(outcome);
        assertNotNull(outcome.getTraceId());
        assertEquals(query, outcome.getOriginalQuery());
        assertEquals(topK, outcome.getTopK());
        assertNotNull(outcome.getSources());
        assertEquals(1, outcome.getSources().size());
        verify(ragTraceRepository).save(any());
    }

    @Test
    void testSearchWithTrace_NullScope() {
        // Given
        String query = "test query";
        int topK = 5;

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            ragService.searchWithTrace(query, topK, null);
        });
    }

    @Test
    void testSearchWithTrace_InvalidTopK() {
        // Given
        String query = "test query";
        int topK = 0; // Invalid, should use default
        TenantScopeVO scope = TenantScopeVO.builder()
                .tenantId("tenant-1")
                .ownerUserId("user-1")
                .build();

        when(embeddingService.embed(eq(query))).thenReturn(new float[]{0.1f});
        when(vectorStoreRepository.search(any(), eq(5), eq(scope)))
                .thenReturn(Collections.emptyList());

        // When
        SearchOutcomeVO outcome = ragService.searchWithTrace(query, topK, scope);

        // Then
        assertEquals(5, outcome.getTopK()); // Should use default value
    }

    @Test
    void testDeleteDocument_Success() {
        // Given
        String documentId = "doc-123";
        TenantScopeVO scope = TenantScopeVO.builder()
                .tenantId("tenant-1")
                .ownerUserId("user-1")
                .build();

        // When
        ragService.deleteDocument(documentId, scope);

        // Then
        verify(vectorStoreRepository).deleteByDocumentId(eq(documentId), eq(scope));
        verify(bm25SearchService).removeDocument(eq(documentId), eq(scope));
        verify(documentMetadataRepository).markDeletedByDocumentId(eq(documentId), eq(scope));
    }

    @Test
    void testHealthCheck_Success() {
        // Given
        when(vectorStoreRepository.healthCheck()).thenReturn(true);

        // When
        boolean result = ragService.healthCheck();

        // Then
        assertTrue(result);
        verify(vectorStoreRepository).healthCheck();
    }

    @Test
    void testSearchWithDetails_AllSearchMethodsAvailable() {
        // Given
        String query = "test query";
        int topK = 5;
        TenantScopeVO scope = TenantScopeVO.builder()
                .tenantId("tenant-1")
                .ownerUserId("user-1")
                .build();

        VectorSearchResultVO vectorResult = VectorSearchResultVO.builder()
                .content("Vector result")
                .score(0.9f)
                .build();

        VectorSearchResultVO bm25Result = VectorSearchResultVO.builder()
                .content("BM25 result")
                .score(0.8f)
                .build();

        VectorSearchResultVO hybridResult = VectorSearchResultVO.builder()
                .content("Hybrid result")
                .score(0.95f)
                .build();

        when(hybridSearchService.isAvailable()).thenReturn(true);
        when(hybridSearchService.vectorSearch(eq(query), eq(topK), eq(scope)))
                .thenReturn(Arrays.asList(vectorResult));
        when(bm25SearchService.search(eq(query), eq(topK), eq(scope)))
                .thenReturn(Arrays.asList(bm25Result));
        when(hybridSearchService.search(eq(query), eq(topK), eq(scope)))
                .thenReturn(Arrays.asList(hybridResult));

        // When
        SearchResultDetailVO result = ragService.searchWithDetails(query, topK, scope);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getVectorResults().size());
        assertEquals(1, result.getBm25Results().size());
        assertEquals(1, result.getHybridResults().size());
    }

    @Test
    void testSearchWithDetails_HybridSearchUnavailable() {
        // Given
        String query = "test query";
        int topK = 5;
        TenantScopeVO scope = TenantScopeVO.builder()
                .tenantId("tenant-1")
                .ownerUserId("user-1")
                .build();

        when(hybridSearchService.isAvailable()).thenReturn(false);

        float[] queryVector = new float[]{0.1f, 0.2f};
        VectorSearchResultVO vectorResult = VectorSearchResultVO.builder()
                .content("Vector result")
                .score(0.9f)
                .build();

        when(embeddingService.embedBatch(anyList()))
                .thenReturn(Arrays.asList(queryVector));
        when(vectorStoreRepository.search(eq(queryVector), eq(topK), eq(scope)))
                .thenReturn(Arrays.asList(vectorResult));

        // When
        SearchResultDetailVO result = ragService.searchWithDetails(query, topK, scope);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getVectorResults().size());
        verify(hybridSearchService, never()).vectorSearch(any(), anyInt(), any());
    }
}

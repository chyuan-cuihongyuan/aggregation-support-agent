package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.knowledgegraph.service.IKnowledgeGraphService;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.GraphSearchResultVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParserFactory;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IRagTraceRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.domain.rag.model.entity.RagTraceEntity;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;
import cn.chyuan.ai.domain.rag.model.valobj.SearchOutcomeVO;
import cn.chyuan.ai.domain.rag.model.valobj.SearchResultDetailVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.chunker.ParentChildChunker;
import cn.chyuan.ai.domain.rag.service.chunker.SemanticChunker;
import cn.chyuan.ai.domain.rag.service.evaluation.AnswerQualityEvaluator;
import cn.chyuan.ai.domain.rag.service.fusion.IResultFusionService;
import cn.chyuan.ai.domain.rag.service.query.IQueryOptimizationService;
import cn.chyuan.ai.domain.rag.service.rerank.IRerankService;
import cn.chyuan.ai.domain.rag.service.reorder.LostInTheMiddleReorderer;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 增强版RAG服务 — 集成四层优化框架
 * <p>
 * 四层优化：
 * <ul>
 *   <li>索引层：Parent-Child分块，小块检索、大块使用</li>
 *   <li>查询层：Query改写、Multi-Query扩展</li>
 *   <li>召回层：向量检索 + BM25多路召回 + RRF融合</li>
 *   <li>重排序层：Cross-encoder Rerank精排</li>
 * </ul>
 */
@Slf4j
@Primary
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = false)
public class EnhancedRagService implements IRagService {

    /** 检索返回的最相似文档数量 */
    @Value("${rag.top-k:5}")
    private int defaultTopK;

    /** 向量检索返回数量（用于多路召回） */
    @Value("${rag.retrieval.vector.top-k:20}")
    private int vectorTopK;

    /** BM25检索返回数量 */
    @Value("${rag.retrieval.bm25.top-k:20}")
    private int bm25TopK;

    /** 是否启用Query优化 */
    @Value("${rag.query.rewrite.enabled:false}")
    private boolean queryRewriteEnabled;

    /** 是否启用Multi-Query */
    @Value("${rag.query.multi-query.enabled:false}")
    private boolean multiQueryEnabled;

    /** Multi-Query扩展数量 */
    @Value("${rag.query.multi-query.count:3}")
    private int multiQueryCount;

    /** 是否启用BM25多路召回 */
    @Value("${rag.retrieval.bm25.enabled:false}")
    private boolean bm25Enabled;

    /** 是否启用Rerank */
    @Value("${rag.rerank.enabled:false}")
    private boolean rerankEnabled;

    /** 最终返回给LLM的chunk数量 */
    @Value("${rag.rerank.top-k:5}")
    private int rerankTopK;

    /** 是否启用Lost in the Middle重排 */
    @Value("${rag.reorder.enabled:false}")
    private boolean reorderEnabled;

    /** 是否启用答案质量评估 */
    @Value("${rag.evaluation.enabled:false}")
    private boolean evaluationEnabled;

    /** 是否启用知识图谱检索 */
    @Value("${knowledge-graph.enabled:true}")
    private boolean knowledgeGraphEnabled;

    /** 知识图谱检索返回数量 */
    @Value("${knowledge-graph.search.entity-top-k:10}")
    private int graphEntityTopK;

    /** 知识图谱子图遍历深度 */
    @Value("${knowledge-graph.search.default-depth:2}")
    private int graphDefaultDepth;

    @Resource
    private IEmbeddingService embeddingService;

    @Resource
    private IVectorStoreRepository vectorStoreRepository;

    @Resource
    private IDocumentParserFactory documentParserFactory;

    @Resource
    private IDocumentMetadataRepository documentMetadataRepository;

    @Resource
    private SemanticChunker semanticChunker;

    @Resource
    private ParentChildChunker parentChildChunker;

    @Resource
    private IQueryOptimizationService queryOptimizationService;

    @Resource
    private IBM25SearchService bm25SearchService;

    @Resource
    private IResultFusionService resultFusionService;

    @Autowired(required = false)
    private IRerankService rerankService;

    @Autowired(required = false)
    private IKnowledgeGraphService knowledgeGraphService;

    @Resource
    private LostInTheMiddleReorderer lostInTheMiddleReorderer;

    @Resource
    private AnswerQualityEvaluator answerQualityEvaluator;

    @Resource
    private IRagTraceRepository ragTraceRepository;

    @Override
    public void uploadDocument(DocumentUploadCommand command) {
        String documentId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long fileSize = command.getRawContent() != null ? command.getRawContent().length
                : (command.getContent() != null ? command.getContent().length() : 0L);
        String extension = resolveExtension(command.getFileName());

        DocumentMetadataEntity metadata = DocumentMetadataEntity.builder()
                .documentId(documentId)
                .tenantId(command.getTenantId() != null ? command.getTenantId() : command.getUserId())
                .ownerUserId(command.getUserId() != null ? command.getUserId() : "")
                .visibility("private")
                .deletedFlag(0)
                .fileName(command.getFileName())
                .fileExtension(extension)
                .fileSize(fileSize)
                .mimeType(command.getMimeType())
                .processingStatus("processing")
                .userId(command.getUserId() != null ? command.getUserId() : "")
                .build();
        documentMetadataRepository.save(metadata);

        // 优先使用 rawContent（支持 PDF/Word/HTML 等二进制格式），兼容旧版本使用 content
        byte[] documentBytes;
        if (command.getRawContent() != null) {
            documentBytes = command.getRawContent();
            log.info("开始处理文档上传(二进制): fileName={}, size={}", command.getFileName(), documentBytes.length);
        } else if (command.getContent() != null) {
            documentBytes = command.getContent().getBytes(StandardCharsets.UTF_8);
            log.info("开始处理文档上传(文本): fileName={}, contentLength={}", command.getFileName(), command.getContent().length());
        } else {
            throw new IllegalArgumentException("文档内容不能为空：既没有 rawContent 也没有 content");
        }

        // 1. 解析文档：根据文件类型自动选择解析器（支持 PDF/Word/HTML/TXT/MD）
        ParsedDocumentVO parsedDocument = documentParserFactory.parse(
                documentBytes,
                command.getFileName(),
                command.getMimeType()
        );

        // 2. 分块：使用语义分块或Parent-Child分块
        List<DocumentChunkEntity> chunks;
        if (isParentChildEnabled()) {
            // Parent-Child分块：只索引子chunk
            ParentChildChunker.ParentChildChunks parentChildChunks = parentChildChunker.chunk(parsedDocument, command.getFileName());
            chunks = parentChildChunks.getChildChunks();

            // 存储父chunk到元数据（用于检索后获取完整上下文）
            // 实际生产中可能需要单独存储父chunk
            log.info("使用Parent-Child分块: childCount={}, parentCount={}",
                    parentChildChunks.getChildChunks().size(), parentChildChunks.getParentChunks().size());
        } else {
            // 使用语义分块
            chunks = semanticChunker.chunk(parsedDocument, command.getFileName());
        }

        if (chunks.isEmpty()) {
            log.warn("文档分块结果为空，跳过处理: {}", command.getFileName());
            documentMetadataRepository.updateStatus(documentId, "success", 0, 0, 0, "文档分块结果为空");
            return;
        }

        for (DocumentChunkEntity chunk : chunks) {
            enrichChunkMetadata(chunk, documentId, metadata);
        }

        // 3. 批量嵌入：将所有分块文本转换为向量
        List<String> texts = chunks.stream()
                .map(DocumentChunkEntity::getContent)
                .collect(Collectors.toList());
        List<float[]> vectors = embeddingService.embedBatch(texts);

        // 4. 将向量写回分块实体
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setVector(vectors.get(i));
        }

        // 5. 写入向量数据库
        vectorStoreRepository.insertChunks(chunks);

        // 6. 如果启用BM25，同时添加到BM25索引
        if (bm25Enabled) {
            addToBM25Index(chunks);
        }

        int totalChars = parsedDocument.getTextContent() != null ? parsedDocument.getTextContent().length() : 0;
        int sectionCount = parsedDocument.getSections() != null ? parsedDocument.getSections().size() : 0;
        documentMetadataRepository.updateStatus(documentId, "success", chunks.size(), totalChars, sectionCount, "");
        log.info("文档上传处理完成: fileName={}, chunkCount={}", command.getFileName(), chunks.size());
    }

    @Override
    public void deleteDocument(String documentId, TenantScopeVO scope) {
        vectorStoreRepository.deleteByDocumentId(documentId, scope);
        if (bm25Enabled) {
            bm25SearchService.removeDocument(documentId, scope);
        }
        documentMetadataRepository.markDeletedByDocumentId(documentId, scope);
    }

    private String resolveExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }

    private void enrichChunkMetadata(DocumentChunkEntity chunk, String documentId, DocumentMetadataEntity metadataEntity) {
        Map<String, Object> metadata = chunk.getMetadata();
        metadata.put("documentId", documentId);
        metadata.put("tenantId", metadataEntity.getTenantId());
        metadata.put("ownerUserId", metadataEntity.getOwnerUserId());
        metadata.put("visibility", metadataEntity.getVisibility());
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK, TenantScopeVO scope) {
        // 复用统一的内部检索流程，避免与 searchWithTrace 出现两套实现
        return doSearchInternal(query, topK, scope).results;
    }

    /**
     * 统一的检索内部流程 — 供 search 与 searchWithTrace 共用
     * <p>
     * 流程：Query 改写 → 多路召回（向量 + BM25 + 可选 Multi-Query）→ Rerank → Lost-in-the-Middle 重排
     *
     * @param query 用户查询文本
     * @param topK  返回结果数量
     * @param scope 租户作用域
     * @return 内部检索输出（包含改写后的 query、最终结果与 rerank 是否生效）
     */
    private InternalSearchOutput doSearchInternal(String query, int topK, TenantScopeVO scope) {
        log.info("开始检索: query={}, topK={}", query, topK);

        // 第二层：查询优化（未启用时返回原 query）
        String optimizedQuery = optimizeQuery(query);
        // 记录是否真正发生了改写：启用且与原 query 不同
        String rewriteQuery = (queryRewriteEnabled && optimizedQuery != null && !optimizedQuery.equals(query))
                ? optimizedQuery : null;

        // 第三层：多路召回
        List<VectorSearchResultVO> results = multiPathRetrieval(optimizedQuery, topK, scope);

        // 第四层：Rerank 精排
        boolean rerankApplied = false;
        if (rerankEnabled && rerankService != null && rerankService.isAvailable()) {
            results = rerankService.rerank(optimizedQuery, results, topK);
            rerankApplied = true;
        }

        // Lost in the Middle 重排：优化 chunk 排列顺序，提升 LLM 对关键内容的关注度
        if (reorderEnabled) {
            results = lostInTheMiddleReorderer.reorder(results);
            log.debug("Lost in the Middle 重排完成");
        }

        log.info("检索完成: resultCount={}, rerankApplied={}", results.size(), rerankApplied);
        return new InternalSearchOutput(rewriteQuery, results, rerankApplied);
    }

    /** 内部检索输出 — 仅在 service 内部使用 */
    private static final class InternalSearchOutput {
        final String rewriteQuery;
        final List<VectorSearchResultVO> results;
        final boolean rerankApplied;

        InternalSearchOutput(String rewriteQuery, List<VectorSearchResultVO> results, boolean rerankApplied) {
            this.rewriteQuery = rewriteQuery;
            this.results = results;
            this.rerankApplied = rerankApplied;
        }
    }

    @Override
    public SearchOutcomeVO searchWithTrace(String query, int topK, TenantScopeVO scope) {
        // 强校验租户作用域，禁止跨租户检索
        if (scope == null || scope.getTenantId() == null || scope.getTenantId().isEmpty()) {
            throw new IllegalArgumentException("租户作用域不能为空");
        }

        String traceId = UUID.randomUUID().toString().replace("-", "");
        log.info("开始带证据链检索: traceId={}, query={}, topK={}", traceId, query, topK);

        // 复用统一的内部检索流程
        InternalSearchOutput internal = doSearchInternal(query, topK, scope);
        List<VectorSearchResultVO> rawResults = internal.results != null ? internal.results : Collections.emptyList();
        // retrievalType: 经过 Rerank 标注为 rerank，否则标注为 hybrid（混合检索结果）
        String retrievalType = internal.rerankApplied ? "rerank" : "hybrid";

        // 将原始检索结果映射为证据 VO
        List<RagSourceVO> sources = rawResults.stream()
                .map(r -> toRagSourceVO(r, retrievalType))
                .collect(Collectors.toList());

        // 异步落库 RagTrace（@Async("ragTraceExecutor")），Repository 内部全量 try/catch，异常不会传播到此
        TenantScopeVO ctxScope = RequestScopeContext.get();
        RagTraceEntity entity = RagTraceEntity.builder()
                .traceId(traceId)
                .tenantId(scope.getTenantId())
                .ownerUserId(scope.getOwnerUserId() != null ? scope.getOwnerUserId() : "")
                .sessionId("")
                .agentId("")
                .queryText(query)
                .rewriteText(internal.rewriteQuery)
                .retrievalTopk(topK)
                .sources(sources)
                .createTime(new Date())
                .build();
        // 兼容：如果 RequestScopeContext 后续扩展出会话/智能体上下文，可以在此覆盖
        if (ctxScope != null && entity.getOwnerUserId().isEmpty() && ctxScope.getOwnerUserId() != null) {
            entity.setOwnerUserId(ctxScope.getOwnerUserId());
        }
        ragTraceRepository.save(entity);

        // 写入收集器，便于 ChatService 出口取出 traceId 拼到响应
        RagSourceCollector.setTraceId(traceId);

        return SearchOutcomeVO.builder()
                .traceId(traceId)
                .originalQuery(query)
                .rewriteQuery(internal.rewriteQuery)
                .topK(topK)
                .sources(sources)
                .rawResults(rawResults)
                .build();
    }

    /**
     * VectorSearchResultVO → RagSourceVO 映射，snippet 截断为前 200 字符
     */
    private RagSourceVO toRagSourceVO(VectorSearchResultVO result, String retrievalType) {
        Map<String, Object> metadata = result.getMetadata();
        String documentId = metadata != null && metadata.get("documentId") != null
                ? String.valueOf(metadata.get("documentId")) : null;
        String documentName = metadata != null && metadata.get("_source") != null
                ? String.valueOf(metadata.get("_source")) : null;
        String chunkId = metadata != null && metadata.get("chunkId") != null
                ? String.valueOf(metadata.get("chunkId")) : null;
        Integer chunkIndex = metadata != null && metadata.get("chunkIndex") != null
                ? ((Number) metadata.get("chunkIndex")).intValue() : null;

        String content = result.getContent();
        String snippet = content == null ? "" : (content.length() > 200 ? content.substring(0, 200) : content);

        return RagSourceVO.builder()
                .documentId(documentId)
                .documentName(documentName)
                .chunkId(chunkId)
                .chunkIndex(chunkIndex)
                .score(result.getScore())
                .retrievalType(retrievalType)
                .snippet(snippet)
                .build();
    }

    @Override
    public boolean healthCheck() {
        return vectorStoreRepository.healthCheck();
    }

    @Override
    public SearchResultDetailVO searchWithDetails(String query, int topK) {
        return searchWithDetails(query, topK, null);
    }

    @Override
    public SearchResultDetailVO searchWithDetails(String query, int topK, TenantScopeVO scope) {
        log.info("检索测试(EnhancedRagService): query={}, topK={}", query, topK);

        List<VectorSearchResultVO> vectorResults = new ArrayList<>();
        List<VectorSearchResultVO> bm25Results = new ArrayList<>();
        List<VectorSearchResultVO> graphResults = new ArrayList<>();
        List<VectorSearchResultVO> hybridResults;

        try {
            vectorResults = vectorRetrieval(query, topK, scope);
        } catch (Exception e) {
            log.error("向量检索失败: {}", e.getMessage());
        }

        try {
            if (bm25Enabled) {
                bm25Results = bm25Retrieval(query, topK, scope);
            }
        } catch (Exception e) {
            log.error("BM25检索失败: {}", e.getMessage());
        }

        try {
            if (knowledgeGraphEnabled && knowledgeGraphService != null) {
                graphResults = graphRetrieval(query, graphEntityTopK);
            }
        } catch (Exception e) {
            log.error("知识图谱检索失败: {}", e.getMessage());
        }

        List<List<VectorSearchResultVO>> allResults = new ArrayList<>();
        if (!vectorResults.isEmpty()) allResults.add(vectorResults);
        if (!bm25Results.isEmpty()) allResults.add(bm25Results);
        if (!graphResults.isEmpty()) allResults.add(graphResults);

        if (allResults.size() > 1) {
            hybridResults = resultFusionService.rrfFusion(allResults, topK);
        } else {
            hybridResults = allResults.isEmpty() ? new ArrayList<>() : allResults.get(0);
        }

        return SearchResultDetailVO.builder()
                .query(query)
                .vectorResults(convertToItems(vectorResults))
                .bm25Results(convertToItems(bm25Results))
                .graphResults(convertToItems(graphResults))
                .hybridResults(convertToItems(hybridResults))
                .build();
    }

    private List<SearchResultDetailVO.SearchItem> convertToItems(List<VectorSearchResultVO> results) {
        if (results == null) return new ArrayList<>();
        return results.stream().map(r -> {
            String source = r.getMetadata() != null ? (String) r.getMetadata().get("_source") : null;
            Integer chunkIndex = r.getMetadata() != null && r.getMetadata().get("chunkIndex") != null
                    ? ((Number) r.getMetadata().get("chunkIndex")).intValue() : null;
            return SearchResultDetailVO.SearchItem.builder()
                    .content(r.getContent())
                    .score(r.getScore())
                    .source(source)
                    .chunkIndex(chunkIndex)
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * 查询优化（第二层）
     */
    private String optimizeQuery(String query) {
        if (!queryRewriteEnabled) {
            return query;
        }

        try {
            // Query改写
            String rewrittenQuery = queryOptimizationService.rewriteQuery(query, null);
            log.info("Query改写: original={}, rewritten={}", query, rewrittenQuery);
            return rewrittenQuery;
        } catch (Exception e) {
            log.warn("Query优化失败，使用原始查询: {}", e.getMessage());
            return query;
        }
    }

    /**
     * 多路召回（第三层） — 向量检索 + BM25检索 + 知识图谱检索并行执行
     */
    private List<VectorSearchResultVO> multiPathRetrieval(String query, int topK, TenantScopeVO scope) {
        // 并行执行向量检索和BM25检索
        CompletableFuture<List<VectorSearchResultVO>> vectorFuture =
                CompletableFuture.supplyAsync(() -> vectorRetrieval(query, vectorTopK, scope));

        CompletableFuture<List<VectorSearchResultVO>> bm25Future = bm25Enabled
                ? CompletableFuture.supplyAsync(() -> bm25Retrieval(query, bm25TopK, scope))
                : CompletableFuture.completedFuture(Collections.emptyList());

        // 知识图谱检索（第三路）
        CompletableFuture<List<VectorSearchResultVO>> graphFuture =
                (knowledgeGraphEnabled && knowledgeGraphService != null)
                ? CompletableFuture.supplyAsync(() -> graphRetrieval(query, graphEntityTopK))
                : CompletableFuture.completedFuture(Collections.emptyList());

        // 设置超时时间，防止无限阻塞（默认30秒）
        try {
            CompletableFuture.allOf(vectorFuture, bm25Future, graphFuture)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("多路检索超时，使用已完成的结果继续处理");
            // 取消未完成的任务
            vectorFuture.cancel(true);
            bm25Future.cancel(true);
            graphFuture.cancel(true);
        } catch (Exception e) {
            log.error("多路检索异常: {}", e.getMessage());
        }

        List<List<VectorSearchResultVO>> allResults = new ArrayList<>();

        // 安全获取结果，已完成的Future会立即返回，未完成的返回空列表
        List<VectorSearchResultVO> vectorResults = safeGetFutureResult(vectorFuture, "向量检索");
        if (!vectorResults.isEmpty()) {
            allResults.add(vectorResults);
        }

        List<VectorSearchResultVO> bm25Results = safeGetFutureResult(bm25Future, "BM25检索");
        if (!bm25Results.isEmpty()) {
            allResults.add(bm25Results);
        }

        List<VectorSearchResultVO> graphResults = safeGetFutureResult(graphFuture, "知识图谱检索");
        if (!graphResults.isEmpty()) {
            allResults.add(graphResults);
        }

        // Multi-Query扩展检索（如果启用）
        if (multiQueryEnabled) {
            List<VectorSearchResultVO> multiQueryResults = multiQueryRetrieval(query, vectorTopK, scope);
            if (!multiQueryResults.isEmpty()) {
                allResults.add(multiQueryResults);
            }
        }

        // 如果只有一路结果，直接返回
        if (allResults.size() == 1) {
            return allResults.get(0).stream().limit(topK).collect(Collectors.toList());
        }

        // 如果没有结果，返回空列表
        if (allResults.isEmpty()) {
            return Collections.emptyList();
        }

        // RRF融合
        return resultFusionService.rrfFusion(allResults, topK);
    }

    /**
     * 安全获取Future结果，避免异常导致整个流程失败
     */
    private List<VectorSearchResultVO> safeGetFutureResult(CompletableFuture<List<VectorSearchResultVO>> future, String retrievalType) {
        try {
            if (future.isDone() && !future.isCancelled() && !future.isCompletedExceptionally()) {
                return future.get();
            }
        } catch (Exception e) {
            log.warn("{}结果获取失败: {}", retrievalType, e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 向量检索
     */
    private List<VectorSearchResultVO> vectorRetrieval(String query, int topK, TenantScopeVO scope) {
        try {
            float[] queryVector = embeddingService.embed(query);
            List<VectorSearchResultVO> results = vectorStoreRepository.search(queryVector, topK, scope);

            // 标记检索类型
            for (VectorSearchResultVO result : results) {
                result.getMetadata().put("retrievalType", "vector");
            }

            return results;
        } catch (Exception e) {
            log.error("向量检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * BM25检索
     */
    private List<VectorSearchResultVO> bm25Retrieval(String query, int topK, TenantScopeVO scope) {
        try {
            return bm25SearchService.search(query, topK, scope);
        } catch (Exception e) {
            log.error("BM25检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 知识图谱检索 — 实体匹配 + 子图遍历，结果转换为统一格式参与RRF融合
     */
    private List<VectorSearchResultVO> graphRetrieval(String query, int topK) {
        try {
            GraphSearchResultVO graphResult = knowledgeGraphService.graphSearch(query, topK, graphDefaultDepth);
            if (graphResult == null || graphResult.getMatchedEntities() == null
                    || graphResult.getMatchedEntities().isEmpty()) {
                return new ArrayList<>();
            }

            // 将图谱结果转换为 VectorSearchResultVO 格式，参与RRF融合
            List<VectorSearchResultVO> results = new ArrayList<>();
            float score = graphResult.getScore() != null ? graphResult.getScore() : 0.5f;

            // 用子图描述作为内容
            String content = graphResult.getSubgraphDescription();
            if (content == null || content.isEmpty()) {
                content = graphResult.getMatchedEntities().stream()
                        .map(e -> e.getEntityName() + "(" + e.getEntityType() + ")")
                        .collect(Collectors.joining(", "));
            }

            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("retrievalType", "knowledge_graph");
            metadata.put("matchedEntityCount", graphResult.getMatchedEntities().size());
            metadata.put("matchedRelationCount",
                    graphResult.getMatchedRelations() != null ? graphResult.getMatchedRelations().size() : 0);
            metadata.put("subgraphDepth", graphDefaultDepth);

            results.add(VectorSearchResultVO.builder()
                    .content(content)
                    .score(score)
                    .metadata(metadata)
                    .build());

            log.info("知识图谱检索完成: matchedEntities={}, relations={}",
                    graphResult.getMatchedEntities().size(),
                    graphResult.getMatchedRelations() != null ? graphResult.getMatchedRelations().size() : 0);
            return results;
        } catch (Exception e) {
            log.error("知识图谱检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Multi-Query扩展检索 — 所有扩展查询并行执行
     */
    private List<VectorSearchResultVO> multiQueryRetrieval(String originalQuery, int topK, TenantScopeVO scope) {
        try {
            // 扩展查询
            List<String> expandedQueries = queryOptimizationService.expandQuery(originalQuery, multiQueryCount);

            // 并行执行所有扩展查询的向量检索
            List<CompletableFuture<List<VectorSearchResultVO>>> futures = expandedQueries.stream()
                    .map(q -> CompletableFuture.supplyAsync(() -> vectorRetrieval(q, topK / expandedQueries.size() + 1, scope)))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<List<VectorSearchResultVO>> multiResults = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            // 融合多Query结果
            return resultFusionService.rrfFusion(multiResults, topK);
        } catch (Exception e) {
            log.error("Multi-Query检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 添加到BM25索引
     */
    private void addToBM25Index(List<DocumentChunkEntity> chunks) {
        try {
            java.util.Map<String, String> documents = new java.util.HashMap<>();
            java.util.Map<String, java.util.Map<String, Object>> metadataByDocId = new java.util.HashMap<>();
            for (DocumentChunkEntity chunk : chunks) {
                documents.put(chunk.getId(), chunk.getContent());
                metadataByDocId.put(chunk.getId(), chunk.getMetadata());
            }
            bm25SearchService.addDocuments(documents, metadataByDocId);
            log.info("添加到BM25索引: count={}", documents.size());
        } catch (Exception e) {
            log.warn("添加到BM25索引失败: {}", e.getMessage());
        }
    }

    /**
     * 判断是否启用Parent-Child分块
     */
    private boolean isParentChildEnabled() {
        // 可以通过配置控制
        return false; // 暂时禁用，需要更多测试
    }

    /**
     * 带Multi-Query的检索方法
     */
    public List<VectorSearchResultVO> searchWithMultiQuery(String query, int topK) {
        log.info("Multi-Query检索: query={}, topK={}", query, topK);

        // 1. Query扩展
        List<String> expandedQueries = queryOptimizationService.expandQuery(query, multiQueryCount);
        log.info("Query扩展结果: {}", expandedQueries);

        // 2. 对每个查询进行向量检索
        List<List<VectorSearchResultVO>> allResults = new ArrayList<>();
        for (String expandedQuery : expandedQueries) {
            List<VectorSearchResultVO> results = vectorRetrieval(expandedQuery, vectorTopK, null);
            allResults.add(results);
        }

        // 3. RRF融合
        List<VectorSearchResultVO> fusedResults = resultFusionService.rrfFusion(allResults, topK);

        // 4. Rerank精排
        if (rerankEnabled && rerankService != null && rerankService.isAvailable()) {
            fusedResults = rerankService.rerank(query, fusedResults, topK);
        }

        return fusedResults;
    }

    /**
     * 带HyDE的检索方法
     */
    public List<VectorSearchResultVO> searchWithHyDE(String query, int topK) {
        log.info("HyDE检索: query={}, topK={}", query, topK);

        // 1. 生成假设文档
        String hypotheticalDoc = queryOptimizationService.generateHypotheticalDocument(query);
        log.info("HyDE假设文档生成完成: length={}", hypotheticalDoc.length());

        // 2. 用假设文档的向量检索
        List<VectorSearchResultVO> results = vectorRetrieval(hypotheticalDoc, topK, null);

        // 3. Rerank精排（使用原始query）
        if (rerankEnabled && rerankService != null && rerankService.isAvailable()) {
            results = rerankService.rerank(query, results, topK);
        }

        return results;
    }

    /**
     * 带答案质量评估的检索方法
     *
     * @param query 用户查询
     * @param topK  返回结果数量
     * @return 检索结果（包含质量评估）
     */
    public SearchResultWithEvaluation searchWithEvaluation(String query, int topK) {
        log.info("带质量评估的检索: query={}, topK={}", query, topK);

        // 1. 执行检索
        List<VectorSearchResultVO> results = search(query, topK);

        // 2. 提取检索内容
        List<String> retrievedContents = results.stream()
                .map(VectorSearchResultVO::getContent)
                .collect(Collectors.toList());

        // 3. 返回结果（质量评估需要在生成答案后调用）
        return SearchResultWithEvaluation.builder()
                .results(results)
                .retrievedContents(retrievedContents)
                .query(query)
                .build();
    }

    /**
     * 评估生成的答案质量
     *
     * @param query           用户查询
     * @param answer          LLM生成的答案
     * @param retrievedChunks 检索到的内容
     * @return 评估结果
     */
    public AnswerQualityEvaluator.EvaluationResult evaluateAnswer(
            String query, String answer, List<String> retrievedChunks) {
        if (!evaluationEnabled) {
            log.info("答案质量评估已禁用");
            return AnswerQualityEvaluator.EvaluationResult.builder()
                    .overallScore(1.0)
                    .isAcceptable(true)
                    .build();
        }

        return answerQualityEvaluator.evaluate(query, answer, retrievedChunks);
    }

    /**
     * 检索结果（包含质量评估信息）
     */
    @lombok.Data
    @lombok.Builder
    public static class SearchResultWithEvaluation {
        /** 检索结果列表 */
        private List<VectorSearchResultVO> results;
        /** 检索到的内容列表 */
        private List<String> retrievedContents;
        /** 用户查询 */
        private String query;
    }

}

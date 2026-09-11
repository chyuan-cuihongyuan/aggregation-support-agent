package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.knowledgegraph.service.IKnowledgeGraphService;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.GraphSearchResultVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.port.IKeywordSearchPort;
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
import cn.chyuan.ai.domain.rag.service.fusion.RrfFusionService;
import cn.chyuan.ai.domain.rag.service.query.IQueryOptimizationService;
import cn.chyuan.ai.domain.rag.service.rerank.IRerankService;
import cn.chyuan.ai.domain.rag.service.reorder.LostInTheMiddleReorderer;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
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
 *   <li>查询层：Query改写</li>
 *   <li>召回层：向量检索 + BM25多路召回 + RRF融合</li>
 *   <li>重排序层：Cross-encoder Rerank精排</li>
 * </ul>
 */
@Slf4j
@Primary
@Service
@Conditional(cn.chyuan.ai.domain.rag.condition.VectorEngineEnabledCondition.class)
public class EnhancedRagService implements IRagService {

    /** 检索返回的最相似文档数量 */
    @Value("${rag.top-k}")
    private int defaultTopK;

    /** 向量检索返回数量（用于多路召回） */
    @Value("${rag.retrieval.vector.top-k}")
    private int vectorTopK;

    /** BM25检索返回数量 */
    @Value("${rag.retrieval.bm25.top-k}")
    private int bm25TopK;

    /** 是否启用Query优化 */
    @Value("${rag.query.rewrite.enabled}")
    private boolean queryRewriteEnabled;

    /** 是否启用BM25多路召回 */
    @Value("${rag.retrieval.bm25.enabled}")
    private boolean bm25Enabled;

    /** 是否启用Rerank */
    @Value("${rag.rerank.enabled}")
    private boolean rerankEnabled;

    /** 最终返回给LLM的chunk数量 */
    @Value("${rag.rerank.top-k}")
    private int rerankTopK;

    /** 是否启用Lost in the Middle重排 */
    @Value("${rag.reorder.enabled}")
    private boolean reorderEnabled;

    /** 是否启用答案质量评估 */
    @Value("${rag.evaluation.enabled}")
    private boolean evaluationEnabled;

    /** 是否启用知识图谱检索 */
    @Value("${knowledge-graph.enabled}")
    private boolean knowledgeGraphEnabled;

    /** 知识图谱检索返回数量 */
    @Value("${knowledge-graph.search.entity-top-k}")
    private int graphEntityTopK;

    /** 知识图谱子图遍历深度 */
    @Value("${knowledge-graph.search.default-depth}")
    private int graphDefaultDepth;

    /** 融合后返回的候选数量（应大于 rerank topK，给 Rerank 留筛选空间） */
    @Value("${rag.retrieval.fusion.top-k:10}")
    private int fusionTopK;

    /** 是否启用混合检索（工单 0163：关键词路 ES match + RRF 纯函数融合；默认关=单路原行为零回归） */
    @Value("${rag.hybrid-enabled:false}")
    private boolean hybridEnabled;

    /** RRF 平滑因子 k（工单 0163 纯函数内核，默认 60 经验值） */
    @Value("${rag.retrieval.fusion.rrf-k:60}")
    private int hybridRrfK;

    @Value("${rag.retrieval.timeout-ms}")
    private long retrievalTimeoutMs;

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

    /** 关键词检索端口（工单 0163 混合检索关键词路；ES 未启用时不装配，保持单路） */
    @Autowired(required = false)
    private IKeywordSearchPort keywordSearchPort;

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

    @Autowired(required = false)
    @Qualifier("ragRetrievalExecutor")
    private AsyncTaskExecutor ragRetrievalExecutor;

    @Override
    public void uploadDocument(DocumentUploadCommand command) {
        String documentId = command.getDocumentId() != null && !command.getDocumentId().isBlank()
                ? command.getDocumentId()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long fileSize = command.getRawContent() != null ? command.getRawContent().length
                : (command.getContent() != null ? command.getContent().length() : 0L);
        String extension = resolveExtension(command.getFileName());

        DocumentMetadataEntity metadata = DocumentMetadataEntity.builder()
                .documentId(documentId)
                .tenantId(command.getTenantId() != null ? command.getTenantId() : command.getUserId())
                .ownerUserId(command.getUserId() != null ? command.getUserId() : "")
                .knowledgeBaseId(command.getKnowledgeBaseId() != null ? command.getKnowledgeBaseId() : "")
                .knowledgeBaseName(command.getKnowledgeBaseName() != null ? command.getKnowledgeBaseName() : "")
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

        try {
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
                // 区分两种空内容情况：文档无文本 vs 文档解析成功但内容不足
                String textContent = parsedDocument.getTextContent();
                String emptyReason;
                if (textContent == null || textContent.trim().isEmpty()) {
                    emptyReason = "文档内容为空：该PDF可能是扫描件或图片PDF，无法提取文字内容。请上传文字版PDF或先进行OCR处理";
                } else if (textContent.trim().length() < 20) {
                    emptyReason = "文档可提取内容过少（仅" + textContent.trim().length() + "字），可能是扫描件或格式不支持的PDF";
                } else {
                    emptyReason = "文档分块结果为空：内容已解析（" + textContent.length() + "字）但无法生成有效分块";
                }
                log.warn("文档分块结果为空，跳过处理: {}, 原因: {}", command.getFileName(), emptyReason);
                documentMetadataRepository.updateStatus(documentId, "warning", 0, 0, 0, emptyReason);
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

            if (knowledgeGraphEnabled && knowledgeGraphService != null) {
                triggerKnowledgeGraphBuild(documentId, chunks);
            }

            int totalChars = parsedDocument.getTextContent() != null ? parsedDocument.getTextContent().length() : 0;
            int sectionCount = parsedDocument.getSections() != null ? parsedDocument.getSections().size() : 0;
            documentMetadataRepository.updateStatus(documentId, "success", chunks.size(), totalChars, sectionCount, "");
            log.info("文档上传处理完成: fileName={}, documentId={}, chunkCount={}", command.getFileName(), documentId, chunks.size());
        } catch (Exception e) {
            log.error("文档上传处理失败: fileName={}, documentId={}", command.getFileName(), documentId, e);
            String errMsg = e.getMessage() != null
                    ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500)) : "未知错误";
            documentMetadataRepository.updateStatus(documentId, "failed", 0, 0, 0, errMsg);
            throw e;
        }
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
        metadata.put("knowledgeBaseId", metadataEntity.getKnowledgeBaseId());
        metadata.put("knowledgeBaseName", metadataEntity.getKnowledgeBaseName());
        metadata.put("visibility", metadataEntity.getVisibility());
    }

    private void triggerKnowledgeGraphBuild(String documentId, List<DocumentChunkEntity> chunks) {
        try {
            knowledgeGraphService.buildGraphFromDocument(documentId, chunks);
            log.info("已提交知识图谱构建任务: documentId={}, chunkCount={}", documentId, chunks.size());
        } catch (Exception e) {
            log.warn("提交知识图谱构建任务失败: documentId={}, err={}", documentId, e.getMessage());
        }
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
     * 流程：Query 改写 → 多路召回（向量 + BM25）→ Rerank → Lost-in-the-Middle 重排
     *
     * @param query 用户查询文本
     * @param topK  返回结果数量
     * @param scope 租户作用域
     * @return 内部检索输出（包含改写后的 query、最终结果与 rerank 是否生效）
     */
    private InternalSearchOutput doSearchInternal(String query, int topK, TenantScopeVO scope) {
        log.info("开始检索: query={}, topK={}", query, topK);

        // 记录各阶段耗时，用于可观测性上报
        List<Map<String, Object>> stages = new ArrayList<>();

        // 第二层：查询优化（未启用时返回原 query）
        long stepStart = System.currentTimeMillis();
        String optimizedQuery = optimizeQuery(query);
        long rewriteCost = System.currentTimeMillis() - stepStart;
        // 记录是否真正发生了改写：启用且与原 query 不同
        String rewriteQuery = (queryRewriteEnabled && optimizedQuery != null && !optimizedQuery.equals(query))
                ? optimizedQuery : null;
        stages.add(Map.of("stage", "query_rewrite", "count", 1, "costTimeMs", (int) rewriteCost));

        // 第三层：多路召回
        stepStart = System.currentTimeMillis();
        List<VectorSearchResultVO> results = multiPathRetrieval(optimizedQuery, topK, scope);
        long retrievalCost = System.currentTimeMillis() - stepStart;
        stages.add(Map.of("stage", "multi_path_retrieval", "count", results.size(), "costTimeMs", (int) retrievalCost));

        // 第四层：Rerank 精排
        boolean rerankApplied = false;
        if (rerankEnabled && rerankService != null && rerankService.isAvailable()) {
            stepStart = System.currentTimeMillis();
            results = rerankService.rerank(optimizedQuery, results, topK);
            long rerankCost = System.currentTimeMillis() - stepStart;
            stages.add(Map.of("stage", "rerank", "count", results.size(), "costTimeMs", (int) rerankCost));
            rerankApplied = true;
        }

        // Lost in the Middle 重排：优化 chunk 排列顺序，提升 LLM 对关键内容的关注度
        if (reorderEnabled) {
            stepStart = System.currentTimeMillis();
            results = lostInTheMiddleReorderer.reorder(results);
            long reorderCost = System.currentTimeMillis() - stepStart;
            stages.add(Map.of("stage", "litm_reorder", "count", results.size(), "costTimeMs", (int) reorderCost));
            log.debug("Lost in the Middle 重排完成");
        }

        // 写入各阶段耗时到 RagSourceCollector Holder
        RagSourceCollector.Holder holder = RagSourceCollector.currentHolder();
        if (holder != null && !stages.isEmpty()) {
            holder.setRetrievalStages(com.alibaba.fastjson.JSON.toJSONString(stages));
        }

        log.info("检索完成: resultCount={}, rerankApplied={}, stages={}", results.size(), rerankApplied, stages.size());
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
        // 记录检索元数据，供请求出口上报 RAG 检索日志到可观测性服务
        RagSourceCollector.setRetrievalMeta(query, internal.rewriteQuery, topK);

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
            String knowledgeBaseId = r.getMetadata() != null && r.getMetadata().get("knowledgeBaseId") != null
                    ? String.valueOf(r.getMetadata().get("knowledgeBaseId")) : null;
            String knowledgeBaseName = r.getMetadata() != null && r.getMetadata().get("knowledgeBaseName") != null
                    ? String.valueOf(r.getMetadata().get("knowledgeBaseName")) : null;
            return SearchResultDetailVO.SearchItem.builder()
                    .content(r.getContent())
                    .score(r.getScore())
                    .source(source)
                    .chunkIndex(chunkIndex)
                    .knowledgeBaseId(knowledgeBaseId)
                    .knowledgeBaseName(knowledgeBaseName)
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
        // 收集所有并行检索任务
        List<CompletableFuture<List<VectorSearchResultVO>>> allFutures = new ArrayList<>();
        List<String> futureLabels = new ArrayList<>();

        // 第一路：向量检索
        CompletableFuture<List<VectorSearchResultVO>> vectorFuture =
                supplyRetrievalAsync(() -> vectorRetrieval(query, vectorTopK, scope));
        allFutures.add(vectorFuture);
        futureLabels.add("向量检索");

        // 第二路：BM25检索 — 混合检索开启（工单 0163）时优先走关键词 ES match 端口
        CompletableFuture<List<VectorSearchResultVO>> bm25Future;
        if (hybridEnabled) {
            bm25Future = (keywordSearchPort != null && keywordSearchPort.isAvailable())
                    ? supplyRetrievalAsync(() -> keywordSearch(query, bm25TopK, scope))
                    : CompletableFuture.completedFuture(Collections.emptyList());
        } else {
            // 默认关：保持既有 BM25 开关语义，单路=原行为零回归
            bm25Future = bm25Enabled
                    ? supplyRetrievalAsync(() -> bm25Retrieval(query, bm25TopK, scope))
                    : CompletableFuture.completedFuture(Collections.emptyList());
        }
        allFutures.add(bm25Future);
        futureLabels.add(hybridEnabled ? "关键词检索" : "BM25检索");

        // 第三路：知识图谱检索
        CompletableFuture<List<VectorSearchResultVO>> graphFuture =
                (knowledgeGraphEnabled && knowledgeGraphService != null)
                ? supplyRetrievalAsync(() -> graphRetrieval(query, graphEntityTopK))
                : CompletableFuture.completedFuture(Collections.emptyList());
        allFutures.add(graphFuture);
        futureLabels.add("知识图谱检索");

        // 统一超时等待所有并行任务
        try {
            CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]))
                    .get(retrievalTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("多路检索超时，使用已完成的结果继续处理");
            allFutures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            log.error("多路检索异常: {}", e.getMessage());
        }

        // 安全获取结果
        List<List<VectorSearchResultVO>> allResults = new ArrayList<>();
        for (int i = 0; i < allFutures.size(); i++) {
            List<VectorSearchResultVO> result = safeGetFutureResult(allFutures.get(i), futureLabels.get(i));
            if (!result.isEmpty()) {
                allResults.add(result);
            }
        }

        // 如果只有一路结果，直接返回（单路=原行为：混合开关关闭或另一路为空时走到这里）
        if (allResults.size() == 1) {
            return allResults.get(0).stream().limit(topK).collect(Collectors.toList());
        }

        // 如果没有结果，返回空列表
        if (allResults.isEmpty()) {
            return Collections.emptyList();
        }

        // 混合检索开启（工单 0163）：domain 纯函数 RrfFusionService 融合（k 可配，rank 从 1 计）
        if (hybridEnabled) {
            return new RrfFusionService(hybridRrfK).fuse(allResults, topK);
        }

        // RRF融合 — 使用 fusionTopK 而非 topK，确保融合后候选数 > rerankTopK，让 Rerank 有筛选空间
        return resultFusionService.rrfFusion(allResults, fusionTopK);
    }

    /**
     * 关键词路检索（工单 0163）— 经 IKeywordSearchPort 走 ES match 查询，
     * 异常内部降级为空列表，不阻断向量路
     */
    private List<VectorSearchResultVO> keywordSearch(String query, int topK, TenantScopeVO scope) {
        try {
            return keywordSearchPort.search(query, topK, scope);
        } catch (Exception e) {
            log.error("关键词检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
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

    private CompletableFuture<List<VectorSearchResultVO>> supplyRetrievalAsync(java.util.function.Supplier<List<VectorSearchResultVO>> supplier) {
        if (ragRetrievalExecutor != null) {
            return CompletableFuture.supplyAsync(supplier, ragRetrievalExecutor);
        }
        return CompletableFuture.supplyAsync(supplier);
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

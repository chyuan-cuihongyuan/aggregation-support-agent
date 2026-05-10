package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.chunker.ParentChildChunker;
import cn.chyuan.ai.domain.rag.service.chunker.SemanticChunker;
import cn.chyuan.ai.domain.rag.service.evaluation.AnswerQualityEvaluator;
import cn.chyuan.ai.domain.rag.service.fusion.IResultFusionService;
import cn.chyuan.ai.domain.rag.service.query.IQueryOptimizationService;
import cn.chyuan.ai.domain.rag.service.rerank.IRerankService;
import cn.chyuan.ai.domain.rag.service.reorder.LostInTheMiddleReorderer;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import cn.chyuan.ai.infrastructure.gateway.parser.DocumentParserFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
@Service
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

    @Resource
    private IEmbeddingService embeddingService;

    @Resource
    private IVectorStoreRepository vectorStoreRepository;

    @Resource
    private DocumentParserFactory documentParserFactory;

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

    @Resource(required = false)
    private IRerankService rerankService;

    @Resource
    private LostInTheMiddleReorderer lostInTheMiddleReorderer;

    @Resource
    private AnswerQualityEvaluator answerQualityEvaluator;

    @Override
    public void uploadDocument(DocumentUploadCommand command) {
        log.info("开始处理文档上传: fileName={}, contentLength={}", command.getFileName(), command.getContent().length());

        // 1. 解析文档：根据文件类型自动选择解析器
        ParsedDocumentVO parsedDocument = documentParserFactory.parse(
                command.getContent().getBytes(StandardCharsets.UTF_8),
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
            return;
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

        log.info("文档上传处理完成: fileName={}, chunkCount={}", command.getFileName(), chunks.size());
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK) {
        log.info("开始检索: query={}, topK={}", query, topK);

        // 第二层：查询优化
        String optimizedQuery = optimizeQuery(query);

        // 第三层：多路召回
        List<VectorSearchResultVO> results = multiPathRetrieval(optimizedQuery, topK);

        // 第四层：Rerank精排
        if (rerankEnabled && rerankService != null && rerankService.isAvailable()) {
            results = rerankService.rerank(optimizedQuery, results, topK);
        }

        // Lost in the Middle重排：优化chunk排列顺序，提升LLM对关键内容的关注度
        if (reorderEnabled) {
            results = lostInTheMiddleReorderer.reorder(results);
            log.debug("Lost in the Middle重排完成");
        }

        log.info("检索完成: resultCount={}", results.size());
        return results;
    }

    @Override
    public boolean healthCheck() {
        return vectorStoreRepository.healthCheck();
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
     * 多路召回（第三层）
     */
    private List<VectorSearchResultVO> multiPathRetrieval(String query, int topK) {
        List<List<VectorSearchResultVO>> allResults = new ArrayList<>();

        // 路径1：向量检索
        List<VectorSearchResultVO> vectorResults = vectorRetrieval(query, vectorTopK);
        allResults.add(vectorResults);

        // 路径2：BM25检索（如果启用）
        if (bm25Enabled) {
            List<VectorSearchResultVO> bm25Results = bm25Retrieval(query, bm25TopK);
            allResults.add(bm25Results);
        }

        // 路径3：Multi-Query扩展检索（如果启用）
        if (multiQueryEnabled) {
            List<VectorSearchResultVO> multiQueryResults = multiQueryRetrieval(query, vectorTopK);
            if (!multiQueryResults.isEmpty()) {
                allResults.add(multiQueryResults);
            }
        }

        // 如果只有一路结果，直接返回
        if (allResults.size() == 1) {
            return allResults.get(0).stream().limit(topK).collect(Collectors.toList());
        }

        // RRF融合
        return resultFusionService.rrfFusion(allResults, topK);
    }

    /**
     * 向量检索
     */
    private List<VectorSearchResultVO> vectorRetrieval(String query, int topK) {
        try {
            float[] queryVector = embeddingService.embed(query);
            List<VectorSearchResultVO> results = vectorStoreRepository.search(queryVector, topK);

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
    private List<VectorSearchResultVO> bm25Retrieval(String query, int topK) {
        try {
            return bm25SearchService.search(query, topK);
        } catch (Exception e) {
            log.error("BM25检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Multi-Query扩展检索
     */
    private List<VectorSearchResultVO> multiQueryRetrieval(String originalQuery, int topK) {
        try {
            // 扩展查询
            List<String> expandedQueries = queryOptimizationService.expandQuery(originalQuery, multiQueryCount);

            // 对每个扩展查询进行向量检索
            List<List<VectorSearchResultVO>> multiResults = new ArrayList<>();
            for (String query : expandedQueries) {
                List<VectorSearchResultVO> results = vectorRetrieval(query, topK / expandedQueries.size() + 1);
                multiResults.add(results);
            }

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
            for (DocumentChunkEntity chunk : chunks) {
                documents.put(chunk.getId(), chunk.getContent());
            }
            bm25SearchService.addDocuments(documents);
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
            List<VectorSearchResultVO> results = vectorRetrieval(expandedQuery, vectorTopK);
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
        List<VectorSearchResultVO> results = vectorRetrieval(hypotheticalDoc, topK);

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

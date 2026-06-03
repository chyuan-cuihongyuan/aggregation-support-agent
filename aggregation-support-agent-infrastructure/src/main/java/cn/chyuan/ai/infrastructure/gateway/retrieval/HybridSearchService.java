package cn.chyuan.ai.infrastructure.gateway.retrieval;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.fusion.IResultFusionService;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import cn.chyuan.ai.domain.rag.service.retrieval.IHybridSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 混合检索服务实现 — 结合向量检索和BM25关键词检索
 * <p>
 * 架构：
 * <pre>
 *                    ┌──────────────┐
 *                    │   用户Query   │
 *                    └──────┬───────┘
 *                           │
 *            ┌──────────────┼──────────────┐
 *            ▼              ▼              ▼
 *     ┌──────────┐   ┌──────────┐   ┌──────────┐
 *     │ 向量检索  │   │  BM25    │   │  融合    │
 *     │(Milvus)  │   │(Lucene)  │   │  (RRF)  │
 *     └────┬─────┘   └────┬─────┘   └────┬─────┘
 *          │              │              │
 *          └──────────────┼──────────────┘
 *                         ▼
 *                  ┌──────────────┐
 *                  │  最终结果    │
 *                  └──────────────┘
 * </pre>
 */
@Slf4j
@Service
public class HybridSearchService implements IHybridSearchService {

    @Value("${rag.retrieval.hybrid.enabled}")
    private boolean hybridEnabled;

    @Value("${rag.retrieval.hybrid.vector-weight}")
    private double defaultVectorWeight;

    @Value("${rag.retrieval.hybrid.bm25-weight}")
    private double defaultBm25Weight;

    @Value("${rag.retrieval.vector.top-k}")
    private int vectorTopK;

    @Value("${rag.retrieval.bm25.top-k}")
    private int bm25TopK;

    @Resource
    private IEmbeddingService embeddingService;

    @Resource
    private IVectorStoreRepository vectorStoreRepository;

    @Resource
    private IBM25SearchService bm25SearchService;

    @Resource
    private IResultFusionService resultFusionService;

    @Override
    public List<VectorSearchResultVO> search(String query, int topK) {
        return search(query, topK, defaultVectorWeight, defaultBm25Weight, null);
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK, TenantScopeVO scope) {
        return search(query, topK, defaultVectorWeight, defaultBm25Weight, scope);
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK, double vectorWeight, double bm25Weight) {
        return search(query, topK, vectorWeight, bm25Weight, null);
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK, double vectorWeight, double bm25Weight, TenantScopeVO scope) {
        log.info("混合检索: query={}, topK={}, vectorWeight={}, bm25Weight={}",
                query, topK, vectorWeight, bm25Weight);

        // 1. 向量检索
        List<VectorSearchResultVO> vectorResults = vectorSearch(query, vectorTopK, scope);
        log.debug("向量检索结果: count={}", vectorResults.size());

        // 2. BM25检索
        List<VectorSearchResultVO> bm25Results = bm25Search(query, bm25TopK, scope);
        log.debug("BM25检索结果: count={}", bm25Results.size());

        // 3. 融合结果
        List<VectorSearchResultVO> fusedResults;

        if (vectorWeight == bm25Weight) {
            // 等权重，使用标准RRF
            fusedResults = resultFusionService.rrfFusion(
                    Arrays.asList(vectorResults, bm25Results), topK);
        } else {
            // 不等权重，使用加权RRF
            fusedResults = resultFusionService.weightedRRFFusion(
                    Arrays.asList(vectorResults, bm25Results),
                    Arrays.asList(vectorWeight, bm25Weight),
                    topK);
        }

        log.info("混合检索完成: resultCount={}", fusedResults.size());
        return fusedResults;
    }

    @Override
    public List<VectorSearchResultVO> vectorSearch(String query, int topK) {
        return vectorSearch(query, topK, null);
    }

    @Override
    public List<VectorSearchResultVO> vectorSearch(String query, int topK, TenantScopeVO scope) {
        try {
            float[] queryVector = embeddingService.embed(query);
            List<VectorSearchResultVO> results = vectorStoreRepository.search(queryVector, topK, scope);

            // 标记检索类型
            for (VectorSearchResultVO result : results) {
                if (result.getMetadata() == null) {
                    result.setMetadata(new java.util.HashMap<>());
                }
                result.getMetadata().put("retrievalType", "vector");
            }

            return results;
        } catch (Exception e) {
            log.error("向量检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<VectorSearchResultVO> bm25Search(String query, int topK) {
        return bm25Search(query, topK, null);
    }

    @Override
    public List<VectorSearchResultVO> bm25Search(String query, int topK, TenantScopeVO scope) {
        try {
            return bm25SearchService.search(query, topK, scope);
        } catch (Exception e) {
            log.error("BM25检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isAvailable() {
        return hybridEnabled;
    }

}

package cn.chyuan.ai.domain.rag.service.retrieval;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.List;

/**
 * 混合检索服务接口 — 结合向量检索和关键字检索的优势
 * <p>
 * 核心思想：
 * <ul>
 *   <li>向量检索：理解语义，处理同义词</li>
 *   <li>BM25关键词检索：精确匹配，速度快</li>
 *   <li>两种方式盲区互补，通过RRF融合</li>
 * </ul>
 */
public interface IHybridSearchService {

    /**
     * 混合检索 — 同时执行向量检索和BM25检索，然后融合结果
     *
     * @param query 查询文本
     * @param topK  返回结果数量
     * @return 融合后的检索结果
     */
    List<VectorSearchResultVO> search(String query, int topK);

    /**
     * 混合检索 — 支持自定义权重
     *
     * @param query        查询文本
     * @param topK         返回结果数量
     * @param vectorWeight 向量检索权重（0-1）
     * @param bm25Weight   BM25检索权重（0-1）
     * @return 融合后的检索结果
     */
    List<VectorSearchResultVO> search(String query, int topK, double vectorWeight, double bm25Weight);

    /**
     * 仅向量检索
     *
     * @param query 查询文本
     * @param topK  返回结果数量
     * @return 检索结果
     */
    List<VectorSearchResultVO> vectorSearch(String query, int topK);

    /**
     * 仅BM25检索
     *
     * @param query 查询文本
     * @param topK  返回结果数量
     * @return 检索结果
     */
    List<VectorSearchResultVO> bm25Search(String query, int topK);

    /**
     * 判断混合检索是否可用
     *
     * @return true表示可用
     */
    boolean isAvailable();

}

package cn.chyuan.ai.domain.rag.service.retrieval;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.List;

/**
 * BM25检索服务接口 — 基于词频的关键词检索
 * <p>
 * BM25（Best Matching 25）是一种基于TF-IDF的排序函数，
 * 适用于精确词语匹配场景，与向量检索互补
 */
public interface IBM25SearchService {

    /**
     * BM25检索
     *
     * @param query 查询文本
     * @param topK  返回结果数量
     * @return 检索结果列表
     */
    List<VectorSearchResultVO> search(String query, int topK);

    /**
     * 添加文档到BM25索引
     *
     * @param docId   文档ID
     * @param content 文档内容
     */
    void addDocument(String docId, String content);

    /**
     * 批量添加文档
     *
     * @param documents 文档列表（key=docId, value=content）
     */
    void addDocuments(java.util.Map<String, String> documents);

    /**
     * 删除文档
     *
     * @param docId 文档ID
     */
    void removeDocument(String docId);

    /**
     * 清空索引
     */
    void clearIndex();

    /**
     * 获取索引文档数量
     *
     * @return 文档数量
     */
    int getDocumentCount();

}

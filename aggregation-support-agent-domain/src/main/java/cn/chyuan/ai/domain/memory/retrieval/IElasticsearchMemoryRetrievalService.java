package cn.chyuan.ai.domain.memory.retrieval;

import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;

import java.util.List;

/**
 * Elasticsearch 记忆检索服务接口
 * 
 * 利用 ES 的全文检索能力，与向量检索形成混合检索
 */
public interface IElasticsearchMemoryRetrievalService {
    
    /**
     * 基于关键词检索记忆（BM25）
     *
     * @param query   查询内容
     * @param options 检索选项
     * @return 匹配的记忆列表
     */
    List<MemoryMatch> recallByKeywords(String query, RecallOptions options);
    
    /**
     * 混合检索（向量 + 关键词）
     *
     * @param query   查询内容
     * @param options 检索选项
     * @return 融合后的记忆列表
     */
    List<MemoryMatch> hybridRecall(String query, RecallOptions options);
    
    /**
     * 多路检索（向量 + 关键词 + 知识图谱）
     *
     * @param query   查询内容
     * @param options 检索选项
     * @return 融合后的记忆列表
     */
    List<MemoryMatch> multiPathRecall(String query, RecallOptions options);
    
    /**
     * 同步记忆到 Elasticsearch
     *
     * @param memoryId 记忆 ID
     */
    void syncToElasticsearch(String memoryId);
    
    /**
     * 批量同步记忆到 Elasticsearch
     *
     * @param memoryIds 记忆 ID 列表
     */
    void syncBatchToElasticsearch(List<String> memoryIds);
    
    /**
     * 从 Elasticsearch 删除记忆
     *
     * @param memoryId 记忆 ID
     */
    void deleteFromElasticsearch(String memoryId);
    
    /**
     * 重建 Elasticsearch 索引
     *
     * @param userId  用户 ID
     * @param agentId Agent ID
     */
    void rebuildIndex(String userId, String agentId);
}

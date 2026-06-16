package cn.chyuan.ai.domain.memory.adapter.port;

import cn.chyuan.ai.domain.memory.model.valobj.VectorSearchResult;

import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 搜索端口接口
 * 
 * 提供基于 Elasticsearch 的 BM25 关键词检索能力
 */
public interface IElasticsearchSearchPort {
    
    /**
     * 执行 BM25 关键词检索
     * 
     * @param query 查询关键词
     * @param limit 返回结果数量限制
     * @return 检索结果列表
     */
    List<VectorSearchResult> search(String query, int limit);
    
    /**
     * 添加文档到 Elasticsearch 索引
     * 
     * @param memoryId 记忆ID
     * @param content 记忆内容
     * @param metadata 元数据
     */
    void addDocument(String memoryId, String content, Map<String, Object> metadata);
    
    /**
     * 从 Elasticsearch 索引中删除文档
     * 
     * @param memoryId 记忆ID
     */
    void removeDocument(String memoryId);
    
    /**
     * 清空 Elasticsearch 索引
     */
    void clearIndex();
}

package cn.chyuan.ai.domain.memory.adapter.port;

import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;

import java.util.List;

/**
 * 关键词搜索端口接口
 * 
 * 提供基于 Elasticsearch 的 BM25 关键词检索能力
 */
public interface IKeywordSearchPort {
    
    /**
     * 执行 BM25 关键词检索
     * 
     * @param query 查询关键词
     * @param limit 返回结果数量限制
     * @return 匹配结果列表
     */
    List<MemoryMatch> search(String query, int limit);
    
    /**
     * 同步文档到 ES 索引
     * 
     * @param memoryId 记忆ID
     */
    void syncDocument(String memoryId);
    
    /**
     * 从 ES 索引中删除文档
     * 
     * @param memoryId 记忆ID
     */
    void removeDocument(String memoryId);
    
    /**
     * 重建 ES 索引
     * 
     * @param userId  用户ID
     * @param agentId AgentID
     */
    void rebuildIndex(String userId, String agentId);
}

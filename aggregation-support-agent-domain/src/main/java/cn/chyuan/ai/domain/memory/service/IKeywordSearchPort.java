package cn.chyuan.ai.domain.memory.service;

import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;

import java.util.List;

/**
 * 关键词检索端口接口
 * <p>
 * 用于在 domain 层调用 Elasticsearch 等关键词检索服务，由 infrastructure 层实现
 */
public interface IKeywordSearchPort {
    
    /**
     * 关键词检索
     *
     * @param query 查询内容
     * @param limit 返回数量限制
     * @return 匹配的记忆列表
     */
    List<MemoryMatch> search(String query, int limit);
    
    /**
     * 同步文档到索引
     *
     * @param memoryId 记忆 ID
     */
    void syncDocument(String memoryId);
    
    /**
     * 从索引中删除文档
     *
     * @param memoryId 记忆 ID
     */
    void removeDocument(String memoryId);
    
    /**
     * 重建索引
     *
     * @param userId  用户 ID
     * @param agentId Agent ID
     */
    void rebuildIndex(String userId, String agentId);
}

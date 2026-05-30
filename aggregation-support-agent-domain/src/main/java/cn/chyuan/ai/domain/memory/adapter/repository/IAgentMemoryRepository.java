package cn.chyuan.ai.domain.memory.adapter.repository;

import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryEntry;
import cn.chyuan.ai.domain.memory.model.valobj.TenantUserPair;

import java.util.List;

/**
 * Agent 记忆仓储接口
 */
public interface IAgentMemoryRepository {
    
    /**
     * 保存记忆
     */
    void save(AgentMemoryEntity entity);
    
    /**
     * 批量保存记忆
     */
    void saveBatch(List<AgentMemoryEntity> entities);
    
    /**
     * 根据记忆ID查询
     */
    AgentMemoryEntity findByMemoryId(String memoryId);
    
    /**
     * 根据内容哈希检查是否存在
     */
    boolean existsByContentHash(String contentHash, String tenantId, String userId);
    
    /**
     * 根据租户和用户查询记忆
     */
    List<AgentMemoryEntity> findByTenantAndUser(String tenantId, String userId);
    
    /**
     * 根据作用域查询记忆
     */
    List<AgentMemoryEntity> findByScope(String scope);
    
    /**
     * 更新记忆内容
     */
    void updateContent(String memoryId, String content, String contentHash);
    
    /**
     * 软删除记忆
     */
    void softDelete(String memoryId);
    
    /**
     * 删除过期记忆
     */
    int deleteExpired(String tenantId, String userId);
    
    /**
     * 查询相似记忆（用于 Consolidation）
     */
    List<MemoryEntry> searchSimilar(String content, String tenantId, String userId, int limit);
    
    /**
     * 语义检索记忆
     */
    List<AgentMemoryEntity> search(float[] queryEmbedding, String tenantId, String userId, 
                                   String scope, int limit);
    
    /**
     * 获取记忆的向量
     */
    float[] getEmbedding(String memoryId);
    
    /**
     * 插入记忆向量
     */
    void insertWithEmbedding(AgentMemoryEntity entity, float[] embedding);
    
    /**
     * 更新记忆向量
     */
    void updateEmbedding(String memoryId, float[] embedding);
    
    /**
     * 删除记忆向量
     */
    void deleteEmbedding(String memoryId);

    /**
     * 查询所有有效的租户-用户对（去重）。
     * <p>
     * 用于定时任务遍历所有需要整合记忆的租户和用户。
     */
    List<TenantUserPair> findAllTenantUserPairs();
}

package cn.chyuan.ai.domain.memory.adapter.repository;

import cn.chyuan.ai.domain.memory.model.entity.MemoryRelation;

import java.util.List;

/**
 * 记忆图谱仓储接口
 * 用于管理记忆之间的关联关系
 */
public interface IMemoryGraphRepository {
    
    /**
     * 保存记忆关联
     *
     * @param relation 记忆关联
     */
    void saveRelation(MemoryRelation relation);
    
    /**
     * 批量保存记忆关联
     *
     * @param relations 记忆关联列表
     */
    void saveRelations(List<MemoryRelation> relations);
    
    /**
     * 查询记忆的所有关联
     *
     * @param memoryId 记忆ID
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @param maxDepth 最大深度
     * @return 关联的记忆列表
     */
    List<MemoryRelation> findRelations(String memoryId, String tenantId, String userId, int maxDepth);
    
    /**
     * 查询记忆的关联记忆ID
     *
     * @param memoryId 记忆ID
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @return 关联的记忆ID列表
     */
    List<String> findRelatedMemoryIds(String memoryId, String tenantId, String userId);
    
    /**
     * 删除记忆的所有关联
     *
     * @param memoryId 记忆ID
     * @param tenantId 租户ID
     * @param userId   用户ID
     */
    void deleteRelations(String memoryId, String tenantId, String userId);
    
    /**
     * 删除指定记忆关联
     *
     * @param relationId 关联ID
     */
    void deleteRelation(String relationId);
    
    /**
     * 检查记忆节点是否存在
     * @param memoryId 记忆ID
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @return 是否存在
     */
    boolean existsMemory(String memoryId, String tenantId, String userId);
    
    /**
     * 统计用户的记忆关联数量
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @return 关联数量
     */
    long countRelations(String tenantId, String userId);
}

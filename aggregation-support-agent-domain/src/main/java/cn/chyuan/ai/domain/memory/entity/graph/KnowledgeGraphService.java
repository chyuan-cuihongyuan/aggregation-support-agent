package cn.chyuan.ai.domain.memory.entity.graph;

import cn.chyuan.ai.domain.memory.entity.AgentEntityMemoryEntity;
import cn.chyuan.ai.domain.memory.entity.EntityGraph;
import cn.chyuan.ai.domain.memory.entity.ExtractedEntity;

import java.util.List;

/**
 * 知识图谱服务接口
 * 
 * 使用 Neo4j 存储实体和关系，支持多跳查询。
 */
public interface KnowledgeGraphService {
    
    /**
     * 创建或更新实体节点
     */
    void upsertEntity(AgentEntityMemoryEntity entity);
    
    /**
     * 创建关系
     * @param sourceEntityId 源实体ID
     * @param relationType   关系类型
     * @param targetEntityId 目标实体ID
     */
    void createRelation(String sourceEntityId, String relationType, String targetEntityId);
    
    /**
     * 查询实体及其关联（多跳查询）
     * @param entityId 实体ID
     * @param hops     跳数
     * @return 实体图谱
     */
    EntityGraph queryWithRelations(String entityId, int hops);
    
    /**
     * 批量从提取的实体中创建节点和关系
     */
    void ingestExtractedEntities(String tenantId, String userId, List<ExtractedEntity> entities);
    
    /**
     * 删除实体节点及其所有关系
     */
    void deleteEntity(String entityId);
    
    /**
     * 获取用户的所有实体
     * @param userId  用户ID
     * @param agentId AgentID
     * @return 实体列表
     */
    List<AgentEntityMemoryEntity> getAllEntities(String userId, String agentId);
    
    /**
     * 获取实体的关联实体
     * @param entityId 实体ID
     * @param userId   用户ID
     * @param agentId  AgentID
     * @param maxHops  最大跳数
     * @return 关联实体列表
     */
    List<AgentEntityMemoryEntity> getRelatedEntities(String entityId, String userId, String agentId, int maxHops);
}

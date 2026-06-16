package cn.chyuan.ai.domain.memory.service;

import cn.chyuan.ai.domain.memory.adapter.repository.IMemoryGraphRepository;
import cn.chyuan.ai.domain.memory.model.entity.MemoryRelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 记忆图谱服务
 * 管理记忆之间的关联关系，支持基于图谱的记忆检索增强
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "neo4j.enabled", havingValue = "true", matchIfMissing = false)
public class MemoryGraphService {
    
    private final ObjectProvider<IMemoryGraphRepository> memoryGraphRepositoryProvider;
    
    public MemoryGraphService(ObjectProvider<IMemoryGraphRepository> memoryGraphRepositoryProvider) {
        this.memoryGraphRepositoryProvider = memoryGraphRepositoryProvider;
    }
    
    private IMemoryGraphRepository getRepository() {
        return memoryGraphRepositoryProvider.getIfAvailable();
    }
    
    /**
     * 创建记忆关联
     */
    public void createRelation(String sourceMemoryId, String targetMemoryId,
                               MemoryRelation.RelationType relationType,
                               double strength, String description,
                               String tenantId, String userId) {
        IMemoryGraphRepository repo = getRepository();
        if (repo == null) {
            log.debug("记忆图谱仓储不可用，跳过创建关联");
            return;
        }
        
        MemoryRelation relation = MemoryRelation.builder()
            .relationId(UUID.randomUUID().toString())
            .sourceMemoryId(sourceMemoryId)
            .targetMemoryId(targetMemoryId)
            .relationType(relationType)
            .strength(strength)
            .description(description)
            .tenantId(tenantId)
            .userId(userId)
            .createdAt(LocalDateTime.now())
            .build();
        
        repo.saveRelation(relation);
        log.debug("创建记忆关联: {} -> {} [{}]", sourceMemoryId, targetMemoryId, relationType);
    }
    
    /**
     * 获取记忆的关联记忆ID（用于检索增强）
     */
    public List<String> getRelatedMemoryIds(String memoryId, String tenantId, String userId) {
        IMemoryGraphRepository repo = getRepository();
        if (repo == null) {
            return List.of();
        }
        return repo.findRelatedMemoryIds(memoryId, tenantId, userId);
    }
    
    /**
     * 获取记忆的关联详情
     */
    public List<MemoryRelation> getRelatedMemories(String memoryId, String tenantId, String userId, int maxDepth) {
        IMemoryGraphRepository repo = getRepository();
        if (repo == null) {
            return List.of();
        }
        return repo.findRelations(memoryId, tenantId, userId, maxDepth);
    }
    
    /**
     * 删除记忆时清理关联
     */
    public void deleteMemoryRelations(String memoryId, String tenantId, String userId) {
        IMemoryGraphRepository repo = getRepository();
        if (repo == null) {
            log.debug("记忆图谱仓储不可用，跳过清理关联");
            return;
        }
        repo.deleteRelations(memoryId, tenantId, userId);
        log.debug("清理记忆关联: {}", memoryId);
    }
    
    /**
     * 统计用户的记忆关联数量
     */
    public long countRelations(String tenantId, String userId) {
        IMemoryGraphRepository repo = getRepository();
        if (repo == null) {
            return 0;
        }
        return repo.countRelations(tenantId, userId);
    }
}

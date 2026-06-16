package cn.chyuan.ai.infrastructure.adapter.repository.memory;

import cn.chyuan.ai.domain.memory.adapter.repository.IAgentMemoryRepository;
import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryEntry;
import cn.chyuan.ai.domain.memory.model.valobj.TenantUserPair;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.infrastructure.persistent.mapper.memory.AgentMemoryMapper;
import cn.chyuan.ai.infrastructure.dao.po.memory.AgentMemoryPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 记忆 MySQL-only 仓储实现（降级方案）
 * <p>
 * 当 Milvus 不可用时，使用此实现作为降级方案。
 * 不支持向量检索，仅支持基于内容哈希的精确匹配和时间排序。
 */
@Slf4j
@Repository
@ConditionalOnMissingBean(AgentMemoryMilvusRepository.class)
@RequiredArgsConstructor
public class AgentMemoryMySQLRepository implements IAgentMemoryRepository {
    
    private final AgentMemoryMapper agentMemoryMapper;
    private final IEmbeddingService embeddingService;
    
    @Override
    public void save(AgentMemoryEntity entity) {
        AgentMemoryPO po = convertToPO(entity);
        agentMemoryMapper.insert(po);
    }
    
    @Override
    public void saveBatch(List<AgentMemoryEntity> entities) {
        for (AgentMemoryEntity entity : entities) {
            save(entity);
        }
    }
    
    @Override
    public AgentMemoryEntity findByMemoryId(String memoryId) {
        AgentMemoryPO po = agentMemoryMapper.selectActiveByMemoryId(memoryId);
        return po != null ? convertToEntity(po) : null;
    }
    
    @Override
    public List<AgentMemoryEntity> findByIds(List<String> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentMemoryPO> poList = agentMemoryMapper.selectActiveByMemoryIds(memoryIds);
        return poList.stream().map(this::convertToEntity).collect(Collectors.toList());
    }
    
    @Override
    public boolean existsByContentHash(String contentHash, String tenantId, String userId, String scope) {
        return agentMemoryMapper.countByContentHash(contentHash, tenantId, userId, scope) > 0;
    }
    
    @Override
    public List<AgentMemoryEntity> findByTenantAndUser(String tenantId, String userId) {
        List<AgentMemoryPO> poList = agentMemoryMapper.selectActiveByTenantAndUser(tenantId, userId);
        return poList.stream().map(this::convertToEntity).collect(Collectors.toList());
    }
    
    @Override
    public List<AgentMemoryEntity> findByScope(String scope) {
        List<AgentMemoryPO> poList = agentMemoryMapper.selectActiveByScope(scope);
        return poList.stream().map(this::convertToEntity).collect(Collectors.toList());
    }
    
    @Override
    public void updateContent(String memoryId, String content, String contentHash) {
        agentMemoryMapper.updateContent(memoryId, content, contentHash);
    }
    
    @Override
    public void softDelete(String memoryId) {
        agentMemoryMapper.softDelete(memoryId);
    }
    
    @Override
    public int deleteExpired(String tenantId, String userId) {
        return agentMemoryMapper.deleteExpired(tenantId, userId);
    }
    
    @Override
    public List<MemoryEntry> searchSimilar(String content, String tenantId, String userId, String scope, int limit) {
        // MySQL-only 模式下，使用内容哈希进行精确匹配
        // 无法进行语义相似度搜索
        log.warn("MySQL-only 模式不支持语义相似度搜索，返回空结果");
        return Collections.emptyList();
    }
    
    @Override
    public List<AgentMemoryEntity> search(float[] queryEmbedding, String tenantId, String userId, 
                                          String scope, int limit) {
        // MySQL-only 模式下，按时间排序返回最近的记忆
        log.warn("MySQL-only 模式不支持向量检索，按时间排序返回");

        List<AgentMemoryPO> poList = agentMemoryMapper.selectRecentByTenantUserScope(tenantId, userId, scope, limit);
        return poList.stream().map(this::convertToEntity).collect(Collectors.toList());
    }
    
    @Override
    public float[] getEmbedding(String memoryId) {
        // MySQL-only 模式下，重新生成 embedding
        AgentMemoryEntity entity = findByMemoryId(memoryId);
        if (entity != null) {
            return embeddingService.embed(entity.getContent());
        }
        return new float[0];
    }
    
    @Override
    public void insertWithEmbedding(AgentMemoryEntity entity, float[] embedding) {
        // MySQL-only 模式下，只保存到 MySQL
        save(entity);
    }
    
    @Override
    public void updateEmbedding(String memoryId, float[] embedding) {
        // MySQL-only 模式下，不做任何操作
        log.debug("MySQL-only 模式下跳过向量更新: {}", memoryId);
    }
    
    @Override
    public void deleteEmbedding(String memoryId) {
        // MySQL-only 模式下，不做任何操作
        log.debug("MySQL-only 模式下跳过向量删除: {}", memoryId);
    }

    @Override
    public List<TenantUserPair> findAllTenantUserPairs() {
        return agentMemoryMapper.selectDistinctTenantUserPairs().stream()
            .map(row -> TenantUserPair.builder()
                .tenantId((String) row.get("tenant_id"))
                .userId((String) row.get("user_id"))
                .build())
            .collect(Collectors.toList());
    }

    @Override
    public List<AgentMemoryEntity> findByUserIdAndAgentId(String userId, String agentId) {
        List<AgentMemoryPO> poList = agentMemoryMapper.selectActiveByUserIdAndAgentId(userId, agentId);
        return poList.stream().map(this::convertToEntity).collect(Collectors.toList());
    }
    
    /**
     * 实体转持久化对象
     */
    private AgentMemoryPO convertToPO(AgentMemoryEntity entity) {
        AgentMemoryPO po = new AgentMemoryPO();
        po.setMemoryId(entity.getMemoryId());
        po.setTenantId(entity.getTenantId());
        po.setUserId(entity.getUserId());
        po.setAgentId(entity.getAgentId());
        po.setSessionId(entity.getSessionId());
        po.setContent(entity.getContent());
        po.setContentHash(entity.getContentHash());
        po.setMemoryType(entity.getMemoryType().name());
        po.setScope(entity.getScope());
        po.setImportance(entity.getImportance());
        po.setSource(entity.getSource());
        po.setMetadata(entity.getMetadata());
        po.setStatus(entity.getStatus());
        if (entity.getExpiresAt() != null) {
            po.setExpiresAt(LocalDateTime.ofInstant(entity.getExpiresAt(), ZoneId.systemDefault()));
        }
        if (entity.getCreatedAt() != null) {
            po.setCreatedAt(LocalDateTime.ofInstant(entity.getCreatedAt(), ZoneId.systemDefault()));
        }
        if (entity.getUpdatedAt() != null) {
            po.setUpdatedAt(LocalDateTime.ofInstant(entity.getUpdatedAt(), ZoneId.systemDefault()));
        }
        return po;
    }
    
    /**
     * 持久化对象转实体
     */
    private AgentMemoryEntity convertToEntity(AgentMemoryPO po) {
        AgentMemoryEntity entity = AgentMemoryEntity.builder()
            .id(po.getId())
            .memoryId(po.getMemoryId())
            .tenantId(po.getTenantId())
            .userId(po.getUserId())
            .agentId(po.getAgentId())
            .sessionId(po.getSessionId())
            .content(po.getContent())
            .contentHash(po.getContentHash())
            .memoryType(MemoryType.valueOf(po.getMemoryType()))
            .scope(po.getScope())
            .importance(po.getImportance())
            .source(po.getSource())
            .metadata(po.getMetadata())
            .status(po.getStatus())
            .build();
        
        if (po.getExpiresAt() != null) {
            entity.setExpiresAt(po.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        if (po.getCreatedAt() != null) {
            entity.setCreatedAt(po.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        if (po.getUpdatedAt() != null) {
            entity.setUpdatedAt(po.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        
        return entity;
    }
}

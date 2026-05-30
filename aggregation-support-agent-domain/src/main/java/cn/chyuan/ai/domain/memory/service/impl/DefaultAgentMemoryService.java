package cn.chyuan.ai.domain.memory.service.impl;

import cn.chyuan.ai.domain.memory.adapter.port.IMemoryConsolidationGateway;
import cn.chyuan.ai.domain.memory.adapter.port.IMemoryExtractionGateway;
import cn.chyuan.ai.domain.memory.adapter.repository.IAgentMemoryRepository;
import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.enums.ConsolidationAction;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.*;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import com.google.common.hash.Hashing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 记忆服务默认实现
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.memory.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class DefaultAgentMemoryService implements AgentMemoryService {
    
    private final IMemoryExtractionGateway extractionGateway;
    private final IMemoryConsolidationGateway consolidationGateway;
    private final IEmbeddingService embeddingService;
    private final IAgentMemoryRepository memoryRepository;
    
    @Override
    @Async("memoryTaskExecutor")
    public void remember(String content, MemoryOptions options) {
        try {
            // Step 1: 内容哈希 (快速去重)
            String contentHash = Hashing.sha256()
                .hashString(content, StandardCharsets.UTF_8)
                .toString();
            
            // 检查是否已存在相同内容
            if (memoryRepository.existsByContentHash(
                    contentHash, options.getTenantId(), options.getUserId())) {
                log.debug("记忆已存在，跳过: {}", contentHash);
                return;
            }
            
            // Step 2: LLM 提取原子事实
            List<ExtractedFact> facts = extractionGateway.extractFacts(content);
            
            for (ExtractedFact fact : facts) {
                processFact(fact, options);
            }
        } catch (Exception e) {
            log.error("存储记忆失败", e);
            // 降级：直接存储原始内容
            fallbackStore(content, options);
        }
    }
    
    @Override
    @Async("memoryTaskExecutor")
    public void rememberBatch(List<String> contents, MemoryOptions options) {
        for (String content : contents) {
            remember(content, options);
        }
    }
    
    private void processFact(ExtractedFact fact, MemoryOptions options) {
        // Step 3: 检查相似记忆
        List<MemoryEntry> similar = memoryRepository.searchSimilar(
            fact.getContent(), 
            options.getTenantId(), 
            options.getUserId(), 
            5
        );
        
        boolean shouldStore = true;
        
        for (MemoryEntry existing : similar) {
            double similarity = existing.getScore();
            
            if (similarity > 0.85) {
                // Step 4: Consolidation - 决定 keep/update/delete
                ConsolidationDecision decision = consolidationGateway.decide(
                    existing.getEntry().getContent(), 
                    fact.getContent()
                );
                
                switch (decision.getAction()) {
                    case UPDATE:
                        updateMemory(
                            existing.getEntry().getMemoryId(), 
                            decision.getMergedContent()
                        );
                        shouldStore = false;
                        break;
                    case DELETE:
                        deleteMemory(existing.getEntry().getMemoryId());
                        break;
                    case KEEP:
                        shouldStore = false;
                        break;
                    default:
                        break;
                }
                break;
            }
        }
        
        if (shouldStore) {
            storeNewMemory(fact, options);
        }
    }
    
    private void storeNewMemory(ExtractedFact fact, MemoryOptions options) {
        // Step 5: 评估重要性
        Float importance = extractionGateway.assessImportance(fact.getContent());
        
        // Step 6: 生成 Embedding
        String memoryId = UUID.randomUUID().toString();
        float[] embedding = embeddingService.embed(fact.getContent());
        
        // Step 7: 构建实体
        AgentMemoryEntity entity = AgentMemoryEntity.builder()
            .memoryId(memoryId)
            .tenantId(options.getTenantId())
            .userId(options.getUserId())
            .agentId(options.getAgentId())
            .sessionId(options.getSessionId())
            .content(fact.getContent())
            .contentHash(Hashing.sha256()
                .hashString(fact.getContent(), StandardCharsets.UTF_8)
                .toString())
            .memoryType(fact.getType() != null ? fact.getType() : MemoryType.FACT)
            .scope(options.getScope() != null ? options.getScope() : "/")
            .importance(importance)
            .source(options.getSource())
            .metadata(options.getMetadata())
            .status(1)
            .expiresAt(options.getTtl() != null ? 
                Instant.now().plus(options.getTtl()) : null)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        
        // Step 8: 存储到 Milvus (向量)
        memoryRepository.insertWithEmbedding(entity, embedding);
        
        // Step 9: 存储到 MySQL (持久化)
        memoryRepository.save(entity);
        
        log.info("存储记忆成功: {} for user: {}", memoryId, options.getUserId());
    }
    
    @Override
    public List<MemoryMatch> recall(String query, RecallOptions options) {
        // 设置默认值
        applyDefaults(options);
        
        // Step 1: 向量检索
        float[] queryEmbedding = embeddingService.embed(query);
        List<AgentMemoryEntity> candidates = memoryRepository.search(
            queryEmbedding,
            options.getTenantId(),
            options.getUserId(),
            options.getScope(),
            options.getLimit() * 3  // 检索更多候选
        );
        
        // Step 2: 复合评分
        List<MemoryMatch> results = candidates.stream()
            .map(entry -> calculateMatch(entry, queryEmbedding, options))
            .filter(match -> match.getScore() >= options.getMinScore())
            .sorted(Comparator.comparingDouble(MemoryMatch::getScore).reversed())
            .limit(options.getLimit())
            .collect(Collectors.toList());
        
        return results;
    }
    
    private MemoryMatch calculateMatch(AgentMemoryEntity entry, 
                                       float[] queryEmbedding, 
                                       RecallOptions options) {
        float[] entryEmbedding = embeddingService.embed(entry.getContent());
        
        double semanticScore = cosineSimilarity(queryEmbedding, entryEmbedding);
        double recencyScore = calculateRecencyScore(
            entry.getCreatedAt(), 
            options.getRecencyHalfLifeDays()
        );
        double importanceScore = entry.getImportance();
        
        double compositeScore = options.getSemanticWeight() * semanticScore
                              + options.getRecencyWeight() * recencyScore
                              + options.getImportanceWeight() * importanceScore;
        
        return MemoryMatch.builder()
            .entry(entry)
            .score(compositeScore)
            .semanticScore(semanticScore)
            .recencyScore(recencyScore)
            .importanceScore(importanceScore)
            .build();
    }
    
    @Override
    public void forget(String memoryId) {
        memoryRepository.softDelete(memoryId);
        log.info("遗忘记忆: {}", memoryId);
    }
    
    @Override
    public void forgetByScope(String scope) {
        List<AgentMemoryEntity> memories = memoryRepository.findByScope(scope);
        for (AgentMemoryEntity memory : memories) {
            forget(memory.getMemoryId());
        }
        log.info("遗忘作用域下的 {} 条记忆: {}", memories.size(), scope);
    }
    
    @Override
    public ConsolidationReport consolidate(String tenantId, String userId) {
        log.info("开始记忆整合: tenant={}, user={}", tenantId, userId);
        
        ConsolidationReport report = ConsolidationReport.builder()
            .tenantId(tenantId)
            .userId(userId)
            .build();
        
        // 获取用户所有记忆
        List<AgentMemoryEntity> allMemories = memoryRepository
            .findByTenantAndUser(tenantId, userId);
        
        report.setTotalProcessed(allMemories.size());
        
        // 按内容哈希去重
        Map<String, List<AgentMemoryEntity>> groupedByHash = allMemories.stream()
            .collect(Collectors.groupingBy(AgentMemoryEntity::getContentHash));
        
        for (Map.Entry<String, List<AgentMemoryEntity>> entry : groupedByHash.entrySet()) {
            if (entry.getValue().size() > 1) {
                // 保留最新的，删除其他
                AgentMemoryEntity latest = entry.getValue().stream()
                    .max(Comparator.comparing(AgentMemoryEntity::getCreatedAt))
                    .get();
                
                for (AgentMemoryEntity duplicate : entry.getValue()) {
                    if (!duplicate.getMemoryId().equals(latest.getMemoryId())) {
                        deleteMemory(duplicate.getMemoryId());
                        report.incrementDuplicatesRemoved();
                    }
                }
            }
        }
        
        // 向量相似度去重
        for (AgentMemoryEntity memory : allMemories) {
            if (memory.getStatus() == 0) {
                continue;
            }
            
            List<MemoryEntry> similar = memoryRepository.searchSimilar(
                memory.getContent(), tenantId, userId, 5);
            
            for (MemoryEntry similarEntry : similar) {
                if (similarEntry.getScore() > 0.90 
                    && !similarEntry.getEntry().getMemoryId()
                        .equals(memory.getMemoryId())) {
                    
                    ConsolidationDecision decision = consolidationGateway.decide(
                        memory.getContent(), 
                        similarEntry.getEntry().getContent()
                    );
                    
                    if (decision.getAction() == ConsolidationAction.MERGE) {
                        updateMemory(memory.getMemoryId(), decision.getMergedContent());
                        deleteMemory(similarEntry.getEntry().getMemoryId());
                        report.incrementMerged();
                    }
                }
            }
        }
        
        // 清理过期记忆
        int expired = memoryRepository.deleteExpired(tenantId, userId);
        report.incrementExpiredCleaned(expired);
        
        log.info("记忆整合完成: {}", report);
        return report;
    }
    
    // ==================== 辅助方法 ====================
    
    private void applyDefaults(RecallOptions options) {
        if (options.getLimit() == null) {
            options.setLimit(10);
        }
        if (options.getMinScore() == null) {
            options.setMinScore(0.3);
        }
        if (options.getSemanticWeight() == null) {
            options.setSemanticWeight(0.5);
        }
        if (options.getRecencyWeight() == null) {
            options.setRecencyWeight(0.3);
        }
        if (options.getImportanceWeight() == null) {
            options.setImportanceWeight(0.2);
        }
        if (options.getRecencyHalfLifeDays() == null) {
            options.setRecencyHalfLifeDays(30);
        }
    }
    
    private double calculateRecencyScore(Instant createdAt, int halfLifeDays) {
        long ageDays = ChronoUnit.DAYS.between(createdAt, Instant.now());
        return Math.pow(0.5, (double) ageDays / halfLifeDays);
    }
    
    private double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    private void updateMemory(String memoryId, String newContent) {
        String newHash = Hashing.sha256()
            .hashString(newContent, StandardCharsets.UTF_8)
            .toString();
        float[] newEmbedding = embeddingService.embed(newContent);
        
        memoryRepository.updateContent(memoryId, newContent, newHash);
        memoryRepository.updateEmbedding(memoryId, newEmbedding);
    }
    
    private void deleteMemory(String memoryId) {
        memoryRepository.softDelete(memoryId);
    }
    
    private void fallbackStore(String content, MemoryOptions options) {
        try {
            String memoryId = UUID.randomUUID().toString();
            float[] embedding = embeddingService.embed(content);
            
            AgentMemoryEntity entity = AgentMemoryEntity.builder()
                .memoryId(memoryId)
                .tenantId(options.getTenantId())
                .userId(options.getUserId())
                .agentId(options.getAgentId())
                .sessionId(options.getSessionId())
                .content(content)
                .contentHash(Hashing.sha256()
                    .hashString(content, StandardCharsets.UTF_8)
                    .toString())
                .memoryType(MemoryType.FACT)
                .scope(options.getScope() != null ? options.getScope() : "/")
                .importance(0.5f)
                .source(options.getSource())
                .status(1)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
            
            memoryRepository.insertWithEmbedding(entity, embedding);
            memoryRepository.save(entity);
        } catch (Exception e) {
            log.error("降级存储也失败", e);
        }
    }
}

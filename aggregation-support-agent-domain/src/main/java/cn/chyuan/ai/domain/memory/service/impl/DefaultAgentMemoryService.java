package cn.chyuan.ai.domain.memory.service.impl;

import cn.chyuan.ai.domain.memory.adapter.port.IMemoryConsolidationGateway;
import cn.chyuan.ai.domain.memory.adapter.port.IMemoryExtractionGateway;
import cn.chyuan.ai.domain.memory.adapter.repository.IAgentMemoryRepository;
import cn.chyuan.ai.domain.memory.adapter.repository.IMemoryGraphRepository;
import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.entity.MemoryRelation;
import cn.chyuan.ai.domain.memory.model.enums.ConsolidationAction;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.*;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import com.google.common.hash.Hashing;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
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
    private final ObjectProvider<IMemoryGraphRepository> memoryGraphRepositoryProvider;
    
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
                    contentHash, options.getTenantId(), options.getUserId(), options.getScope())) {
                log.debug("记忆已存在，跳过: {}", contentHash);
                return;
            }

            // Step 2: 提取事实
            // EPISODE 类型（对话片段）本身就是完整语义单元，无需再调 LLM 拆分为原子事实，
            // 直接整段存储以节省 LLM 调用成本（高频对话场景下尤为明显）。
            List<ExtractedFact> facts;
            if (options.getMemoryType() == MemoryType.EPISODE) {
                facts = Collections.singletonList(
                        ExtractedFact.builder().content(content).type(MemoryType.EPISODE).build());
            } else {
                // 其他类型（FACT/PREFERENCE/DECISION/KNOWLEDGE）调用 LLM 提取原子事实
                facts = extractionGateway.extractFacts(content);
            }

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
            options.getScope(),
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
    
    /** EPISODE 类型对话记忆的默认重要性（跳过 LLM 评估，给中等偏上权重） */
    private static final float EPISODE_DEFAULT_IMPORTANCE = 0.6f;

    private void storeNewMemory(ExtractedFact fact, MemoryOptions options) {
        // Step 5: 评估重要性
        // EPISODE 类型跳过 LLM 重要性评估，使用默认值，避免高频对话场景的额外 LLM 调用
        Float importance = (fact.getType() == MemoryType.EPISODE)
                ? EPISODE_DEFAULT_IMPORTANCE
                : extractionGateway.assessImportance(fact.getContent());

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
        
        // Step 10: 在 Neo4j 中创建记忆节点并建立关联
        createMemoryGraphRelations(entity, options);
        
        log.info("存储记忆成功: {} for user: {}", memoryId, options.getUserId());
    }
    
    /**
     * 创建记忆图谱关联
     * 将新记忆与相关记忆建立关联关系
     */
    private void createMemoryGraphRelations(AgentMemoryEntity newMemory, MemoryOptions options) {
        IMemoryGraphRepository graphRepo = memoryGraphRepositoryProvider.getIfAvailable();
        if (graphRepo == null) {
            log.debug("记忆图谱仓储不可用，跳过图谱关联创建");
            return;
        }
        
        try {
            // 查找相似记忆（基于向量检索）
            List<MemoryEntry> similarMemories = memoryRepository.searchSimilar(
                newMemory.getContent(),
                options.getTenantId(),
                options.getUserId(),
                options.getScope(),
                5
            );
            
            // 为相似度 > 0.7 的记忆创建关联
            for (MemoryEntry similar : similarMemories) {
                if (similar.getScore() > 0.7 && !similar.getEntry().getMemoryId().equals(newMemory.getMemoryId())) {
                    MemoryRelation.RelationType relationType = determineRelationType(newMemory, similar.getEntry());
                    
                    MemoryRelation relation = MemoryRelation.builder()
                        .relationId(UUID.randomUUID().toString())
                        .sourceMemoryId(newMemory.getMemoryId())
                        .targetMemoryId(similar.getEntry().getMemoryId())
                        .relationType(relationType)
                        .strength(similar.getScore())
                        .description("基于语义相似度自动建立")
                        .tenantId(options.getTenantId())
                        .userId(options.getUserId())
                        .createdAt(LocalDateTime.now())
                        .build();
                    
                    graphRepo.saveRelation(relation);
                    log.debug("创建记忆关联: {} -> {} [{}] 相似度: {}", 
                        newMemory.getMemoryId(), similar.getEntry().getMemoryId(), 
                        relationType, similar.getScore());
                }
            }
        } catch (Exception e) {
            log.warn("创建记忆图谱关联失败，不影响主流程: {}", e.getMessage());
        }
    }
    
    /**
     * 根据记忆类型和内容确定关联类型
     */
    private MemoryRelation.RelationType determineRelationType(AgentMemoryEntity source, AgentMemoryEntity target) {
        // 相同类型的记忆通常是相似关系
        if (source.getMemoryType() == target.getMemoryType()) {
            return MemoryRelation.RelationType.SIMILAR;
        }
        
        // 根据记忆类型组合确定关联类型
        return switch (source.getMemoryType()) {
            case FACT -> MemoryRelation.RelationType.THEMATIC;
            case PREFERENCE -> MemoryRelation.RelationType.CONTEXTUAL;
            case DECISION -> MemoryRelation.RelationType.CAUSAL;
            case EPISODE -> MemoryRelation.RelationType.TEMPORAL;
            case KNOWLEDGE -> MemoryRelation.RelationType.REFERENCE;
            default -> MemoryRelation.RelationType.THEMATIC;
        };
    }
    
    @Override
    public List<MemoryMatch> recall(String query, RecallOptions options) {
        // 设置默认值
        applyDefaults(options);

        // Step 1: 向量检索
        float[] queryEmbedding = embeddingService.embed(query);
        return doRecall(query, queryEmbedding, options);
    }

    @Override
    public List<MemoryMatch> recall(String query, float[] queryEmbedding, RecallOptions options) {
        applyDefaults(options);
        return doRecall(query, queryEmbedding, options);
    }

    /**
     * 内部检索逻辑 — 复用 queryEmbedding，避免重复嵌入
     */
    private List<MemoryMatch> doRecall(String query, float[] queryEmbedding, RecallOptions options) {
        List<AgentMemoryEntity> candidates = memoryRepository.search(
            queryEmbedding,
            options.getTenantId(),
            options.getUserId(),
            options.getScope(),
            options.getLimit() * 3  // 检索更多候选
        );

        // 降级路径优化：对所有缺失 searchScore 的候选，一次性批量嵌入，
        // 避免对每条候选单独调用嵌入 API（候选数为 limit*3 时尤为关键）
        Map<String, float[]> fallbackEmbeddings = batchEmbedMissingScores(candidates);

        // 复合评分（优先使用 Milvus 返回的 searchScore，避免重新嵌入）
        List<MemoryMatch> results = candidates.stream()
            .map(entry -> calculateMatch(entry, queryEmbedding, options, fallbackEmbeddings))
            .filter(match -> match.getScore() >= options.getMinScore())
            .sorted(Comparator.comparingDouble(MemoryMatch::getScore).reversed())
            .limit(options.getLimit())
            .collect(Collectors.toList());

        // 使用图谱增强检索结果
        results = enhanceWithGraph(results, options);

        return results;
    }

    /**
     * 使用记忆图谱增强检索结果
     * 通过图谱关联关系补充相关记忆
     */
    private List<MemoryMatch> enhanceWithGraph(List<MemoryMatch> initialResults, RecallOptions options) {
        IMemoryGraphRepository graphRepo = memoryGraphRepositoryProvider.getIfAvailable();
        if (graphRepo == null) {
            return initialResults;
        }

        try {
            // 收集初始结果中的记忆ID
            Set<String> initialMemoryIds = initialResults.stream()
                .map(match -> match.getEntry().getMemoryId())
                .collect(Collectors.toSet());

            // 通过图谱查找关联记忆
            Set<String> relatedMemoryIds = new HashSet<>();
            for (String memoryId : initialMemoryIds) {
                List<String> related = graphRepo.findRelatedMemoryIds(
                    memoryId,
                    options.getTenantId(),
                    options.getUserId()
                );
                relatedMemoryIds.addAll(related);
            }

            // 移除已存在的记忆ID
            relatedMemoryIds.removeAll(initialMemoryIds);

            if (relatedMemoryIds.isEmpty()) {
                return initialResults;
            }

            // 限制加载数量，避免加载过多关联记忆
            int limit = Math.min(relatedMemoryIds.size(), options.getLimit() - initialResults.size());
            if (limit <= 0) {
                return initialResults;
            }

            // 批量加载关联记忆（避免全量加载）
            List<AgentMemoryEntity> relatedMemories = memoryRepository.findByIds(
                new ArrayList<>(relatedMemoryIds).subList(0, limit)
            );

            // 将关联记忆转换为 MemoryMatch（给予较低的分数）
            List<MemoryMatch> graphEnhancedResults = new ArrayList<>(initialResults);
            for (AgentMemoryEntity memory : relatedMemories) {
                MemoryMatch match = MemoryMatch.builder()
                    .entry(memory)
                    .score(0.6)  // 图谱关联记忆的基础分数
                    .semanticScore(0.6)
                    .recencyScore(1.0)
                    .importanceScore(memory.getImportance() != null ? memory.getImportance().doubleValue() : 0.5)
                    .build();
                graphEnhancedResults.add(match);
            }

            // 重新排序并限制数量
            return graphEnhancedResults.stream()
                .sorted(Comparator.comparingDouble(MemoryMatch::getScore).reversed())
                .limit(options.getLimit())
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("图谱增强检索失败，返回原始结果: {}", e.getMessage());
            return initialResults;
        }
    }

    /**
     * 对缺失 searchScore 的候选批量计算嵌入向量
     * <p>
     * 仅在存储后端（如 MySQL）无法提供向量相似度分数时触发。
     * 通过一次 embedBatch 调用代替 N 次 embed 调用，显著降低降级路径成本。
     *
     * @return contentHash → 嵌入向量 的映射；若全部候选都有 searchScore 则返回空 Map
     */
    private Map<String, float[]> batchEmbedMissingScores(List<AgentMemoryEntity> candidates) {
        List<AgentMemoryEntity> missing = candidates.stream()
            .filter(c -> c.getSearchScore() == null)
            .collect(Collectors.toList());

        if (missing.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> texts = missing.stream()
            .map(AgentMemoryEntity::getContent)
            .collect(Collectors.toList());
        List<float[]> vectors = embeddingService.embedBatch(texts);

        Map<String, float[]> embeddingMap = new HashMap<>();
        for (int i = 0; i < missing.size() && i < vectors.size(); i++) {
            embeddingMap.put(missing.get(i).getContentHash(), vectors.get(i));
        }
        return embeddingMap;
    }

    private MemoryMatch calculateMatch(AgentMemoryEntity entry,
                                       float[] queryEmbedding,
                                       RecallOptions options,
                                       Map<String, float[]> fallbackEmbeddings) {
        // 优先使用 Milvus COSINE 度量返回的相似度分数，避免对每条候选重新调用嵌入 API
        double semanticScore;
        if (entry.getSearchScore() != null) {
            semanticScore = entry.getSearchScore();
        } else {
            // 降级路径：从批量嵌入结果中取出对应向量，本地计算余弦相似度
            float[] entryEmbedding = fallbackEmbeddings.get(entry.getContentHash());
            semanticScore = entryEmbedding != null
                    ? cosineSimilarity(queryEmbedding, entryEmbedding)
                    : 0.0;
        }

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
        // 先获取记忆信息用于清理图谱
        AgentMemoryEntity memory = memoryRepository.findByMemoryId(memoryId);
        if (memory != null) {
            // 清理 Neo4j 图谱关联
            IMemoryGraphRepository graphRepo = memoryGraphRepositoryProvider.getIfAvailable();
            if (graphRepo != null) {
                try {
                    graphRepo.deleteRelations(memoryId, memory.getTenantId(), memory.getUserId());
                    log.debug("已清理记忆图谱关联: {}", memoryId);
                } catch (Exception e) {
                    log.warn("清理记忆图谱关联失败: {}", e.getMessage());
                }
            }
        }
        
        // 软删除记忆
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
        
        // 按内容哈希 + scope 去重（使用复合键避免字符串拼接碰撞）
        Map<Map.Entry<String, String>, List<AgentMemoryEntity>> groupedByHash = allMemories.stream()
            .collect(Collectors.groupingBy(memory -> Map.entry(memory.getContentHash(), safeScope(memory.getScope()))));
        
        for (Map.Entry<Map.Entry<String, String>, List<AgentMemoryEntity>> entry : groupedByHash.entrySet()) {
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
        // 先收集需要处理的记忆ID，避免边删边遍历
        List<AgentMemoryEntity> activeMemories = allMemories.stream()
            .filter(m -> m.getStatus() != null && m.getStatus() == 1)
            .collect(Collectors.toList());
        
        Set<String> processedMemoryIds = new HashSet<>();
        
        for (AgentMemoryEntity memory : activeMemories) {
            if (processedMemoryIds.contains(memory.getMemoryId())) {
                continue;
            }
            
            List<MemoryEntry> similar = memoryRepository.searchSimilar(
                memory.getContent(), tenantId, userId, memory.getScope(), 5);
            
            for (MemoryEntry similarEntry : similar) {
                if (similarEntry.getScore() > 0.90 
                    && !similarEntry.getEntry().getMemoryId()
                        .equals(memory.getMemoryId())
                    && !processedMemoryIds.contains(similarEntry.getEntry().getMemoryId())) {
                    
                    ConsolidationDecision decision = consolidationGateway.decide(
                        memory.getContent(), 
                        similarEntry.getEntry().getContent()
                    );
                    
                    if (decision.getAction() == ConsolidationAction.MERGE) {
                        updateMemory(memory.getMemoryId(), decision.getMergedContent());
                        deleteMemory(similarEntry.getEntry().getMemoryId());
                        processedMemoryIds.add(similarEntry.getEntry().getMemoryId());
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
        
        // 避免零向量导致的除零错误
        if (normA == 0 || normB == 0) {
            return 0.0;
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
            // 防止并发重复写入：先检查内容哈希是否已存在
            String contentHash = Hashing.sha256()
                .hashString(content, StandardCharsets.UTF_8)
                .toString();

            if (memoryRepository.existsByContentHash(contentHash, options.getTenantId(), options.getUserId(), options.getScope())) {
                log.debug("降级存储：记忆已存在（contentHash={}），跳过重复写入", contentHash.substring(0, 8));
                return;
            }

            String memoryId = UUID.randomUUID().toString();

            AgentMemoryEntity entity = AgentMemoryEntity.builder()
                .memoryId(memoryId)
                .tenantId(options.getTenantId())
                .userId(options.getUserId())
                .agentId(options.getAgentId())
                .sessionId(options.getSessionId())
                .content(content)
                .contentHash(contentHash)
                .memoryType(MemoryType.FACT)
                .scope(options.getScope() != null ? options.getScope() : "/")
                .importance(0.5f)
                .source(options.getSource())
                .status(1)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

            // 先落 MySQL，保证记忆文本不丢（降级存储首要目标是保数据）。
            // 主路径 storeNewMemory 因嵌入失败才会走到这里，降级时嵌入大概率仍会失败，
            // 因此向量索引写入单独兜底：嵌入失败则跳过 Milvus（该条暂不可被语义检索，但文本已持久化）。
            memoryRepository.save(entity);
            try {
                float[] embedding = embeddingService.embed(content);
                memoryRepository.insertWithEmbedding(entity, embedding);
            } catch (Exception embedEx) {
                log.warn("降级存储：嵌入失败，记忆文本已写入 MySQL 但跳过向量索引: {}", embedEx.getMessage());
            }
        } catch (Exception e) {
            log.error("降级存储也失败", e);
        }
    }

    private String safeScope(String scope) {
        return scope == null ? "" : scope;
    }

    @Override
    public List<AgentMemoryEntity> getOldMemories(String userId, String agentId, Instant cutoff) {
        List<AgentMemoryEntity> allMemories = memoryRepository.findByUserIdAndAgentId(userId, agentId);
        return allMemories.stream()
            .filter(m -> m.getCreatedAt().isBefore(cutoff))
            .sorted(Comparator.comparing(AgentMemoryEntity::getCreatedAt))
            .collect(Collectors.toList());
    }

    @Override
    public List<AgentMemoryEntity> getAllMemories(String userId, String agentId) {
        return memoryRepository.findByUserIdAndAgentId(userId, agentId);
    }
}

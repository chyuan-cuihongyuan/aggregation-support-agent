package cn.chyuan.ai.infrastructure.gateway.memory;

import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.retrieval.IElasticsearchMemoryRetrievalService;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import cn.chyuan.ai.infrastructure.adapter.repository.memory.AgentMemoryMySQLRepository;
import cn.chyuan.ai.infrastructure.gateway.retrieval.ElasticsearchBM25SearchService;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Elasticsearch 记忆检索服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchMemoryRetrievalServiceImpl implements IElasticsearchMemoryRetrievalService {
    
    private final AgentMemoryService memoryService;
    private final AgentMemoryMySQLRepository mysqlRepository;
    private final ElasticsearchBM25SearchService elasticsearchService;
    
    @Override
    public List<MemoryMatch> recallByKeywords(String query, RecallOptions options) {
        log.info("ES 关键词检索：query={}, limit={}", query, options.getLimit());
        
        try {
            // 使用 BM25 检索
            List<VectorSearchResultVO> results = elasticsearchService.search(
                query, 
                options.getLimit()
            );
            
            // 转换为 MemoryMatch
            return results.stream()
                .map(this::convertToMemoryMatch)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(MemoryMatch::getScore).reversed())
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            log.error("ES 关键词检索失败", e);
            return List.of();
        }
    }
    
    @Override
    public List<MemoryMatch> hybridRecall(String query, RecallOptions options) {
        log.info("混合检索（向量 + 关键词）: query={}", query);
        
        // Step 1: 向量检索
        List<MemoryMatch> vectorResults = memoryService.recall(query, options);
        
        // Step 2: 关键词检索
        List<MemoryMatch> keywordResults = recallByKeywords(query, options);
        
        // Step 3: RRF 融合
        return rrfFusion(vectorResults, keywordResults, options);
    }
    
    @Override
    public List<MemoryMatch> multiPathRecall(String query, RecallOptions options) {
        log.info("多路检索（向量 + 关键词 + 知识图谱）: query={}", query);
        
        // Step 1: 向量检索
        List<MemoryMatch> vectorResults = memoryService.recall(query, options);
        
        // Step 2: 关键词检索
        List<MemoryMatch> keywordResults = recallByKeywords(query, options);
        
        // Step 3: 知识图谱检索
        List<MemoryMatch> graphResults = List.of();
        try {
            graphResults = recallFromKnowledgeGraph(query, options);
        } catch (Exception e) {
            log.warn("知识图谱检索失败，降级", e);
        }
        
        // Step 4: RRF 融合三路结果
        return rrfFusionThreeWay(vectorResults, keywordResults, graphResults, options);
    }
    
    @Override
    public void syncToElasticsearch(String memoryId) {
        log.info("同步记忆到 ES: memoryId={}", memoryId);
        
        try {
            AgentMemoryEntity memory = mysqlRepository.findByMemoryId(memoryId);
            if (memory == null) {
                log.warn("记忆不存在：memoryId={}", memoryId);
                return;
            }
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("memoryId", memory.getMemoryId());
            metadata.put("tenantId", memory.getTenantId());
            metadata.put("userId", memory.getUserId());
            metadata.put("agentId", memory.getAgentId());
            metadata.put("memoryType", memory.getMemoryType().name());
            metadata.put("importance", memory.getImportance());
            metadata.put("createdAt", memory.getCreatedAt().toEpochMilli());
            
            elasticsearchService.addDocument(memoryId, memory.getContent(), metadata);
            
        } catch (Exception e) {
            log.error("同步记忆到 ES 失败：memoryId={}", memoryId, e);
        }
    }
    
    @Override
    public void syncBatchToElasticsearch(List<String> memoryIds) {
        log.info("批量同步记忆到 ES: count={}", memoryIds.size());
        
        for (String memoryId : memoryIds) {
            syncToElasticsearch(memoryId);
        }
    }
    
    @Override
    public void deleteFromElasticsearch(String memoryId) {
        log.info("从 ES 删除记忆：memoryId={}", memoryId);
        
        try {
            elasticsearchService.removeDocument(memoryId);
        } catch (Exception e) {
            log.error("从 ES 删除记忆失败：memoryId={}", memoryId, e);
        }
    }
    
    @Override
    public void rebuildIndex(String userId, String agentId) {
        log.info("重建 ES 索引：userId={}, agentId={}", userId, agentId);
        
        try {
            // 清空索引
            elasticsearchService.clearIndex();
            
            // 获取所有记忆
            List<AgentMemoryEntity> memories = mysqlRepository.findByUserIdAndAgentId(userId, agentId);
            
            // 批量同步
            for (AgentMemoryEntity memory : memories) {
                syncToElasticsearch(memory.getMemoryId());
            }
            
            log.info("ES 索引重建完成：count={}", memories.size());
            
        } catch (Exception e) {
            log.error("重建 ES 索引失败", e);
        }
    }
    
    /**
     * RRF 融合两路结果
     */
    private List<MemoryMatch> rrfFusion(List<MemoryMatch> vectorResults,
                                         List<MemoryMatch> keywordResults,
                                         RecallOptions options) {
        int k = 60; // RRF 平滑参数
        
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, MemoryMatch> matchMap = new HashMap<>();
        
        // 向量检索结果
        for (int i = 0; i < vectorResults.size(); i++) {
            MemoryMatch match = vectorResults.get(i);
            String id = match.getEntry().getMemoryId();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(id, rrfScore, Double::sum);
            matchMap.putIfAbsent(id, match);
        }
        
        // 关键词检索结果
        for (int i = 0; i < keywordResults.size(); i++) {
            MemoryMatch match = keywordResults.get(i);
            String id = match.getEntry().getMemoryId();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(id, rrfScore, Double::sum);
            matchMap.putIfAbsent(id, match);
        }
        
        // 排序并返回 top N
        return scoreMap.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(options.getLimit())
            .map(entry -> {
                MemoryMatch match = matchMap.get(entry.getKey());
                return MemoryMatch.builder()
                    .entry(match.getEntry())
                    .score(entry.getValue())
                    .semanticScore(match.getSemanticScore())
                    .recencyScore(match.getRecencyScore())
                    .importanceScore(match.getImportanceScore())
                    .build();
            })
            .collect(Collectors.toList());
    }
    
    /**
     * RRF 融合三路结果
     */
    private List<MemoryMatch> rrfFusionThreeWay(List<MemoryMatch> vectorResults,
                                                 List<MemoryMatch> keywordResults,
                                                 List<MemoryMatch> graphResults,
                                                 RecallOptions options) {
        int k = 60;
        
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, MemoryMatch> matchMap = new HashMap<>();
        
        // 向量检索
        for (int i = 0; i < vectorResults.size(); i++) {
            MemoryMatch match = vectorResults.get(i);
            String id = match.getEntry().getMemoryId();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(id, rrfScore, Double::sum);
            matchMap.putIfAbsent(id, match);
        }
        
        // 关键词检索
        for (int i = 0; i < keywordResults.size(); i++) {
            MemoryMatch match = keywordResults.get(i);
            String id = match.getEntry().getMemoryId();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(id, rrfScore, Double::sum);
            matchMap.putIfAbsent(id, match);
        }
        
        // 知识图谱检索
        for (int i = 0; i < graphResults.size(); i++) {
            MemoryMatch match = graphResults.get(i);
            String id = match.getEntry().getMemoryId();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(id, rrfScore, Double::sum);
            matchMap.putIfAbsent(id, match);
        }
        
        return scoreMap.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(options.getLimit())
            .map(entry -> {
                MemoryMatch match = matchMap.get(entry.getKey());
                return MemoryMatch.builder()
                    .entry(match.getEntry())
                    .score(entry.getValue())
                    .semanticScore(match.getSemanticScore())
                    .recencyScore(match.getRecencyScore())
                    .importanceScore(match.getImportanceScore())
                    .build();
            })
            .collect(Collectors.toList());
    }
    
    /**
     * 从知识图谱检索
     */
    private List<MemoryMatch> recallFromKnowledgeGraph(String query, RecallOptions options) {
        // TODO: 实现基于知识图谱的检索
        // 目前返回空列表
        return List.of();
    }
    
    /**
     * 转换为 MemoryMatch
     */
    private MemoryMatch convertToMemoryMatch(VectorSearchResultVO result) {
        try {
            Map<String, Object> metadata = result.getMetadata();
            String memoryId = (String) metadata.get("memoryId");
            
            AgentMemoryEntity memory = mysqlRepository.findByMemoryId(memoryId);
            if (memory == null) {
                return null;
            }
            
            return MemoryMatch.builder()
                .entry(memory)
                .score((double) result.getScore())
                .semanticScore(0.0)
                .recencyScore(0.0)
                .importanceScore(memory.getImportance().doubleValue())
                .build();
                
        } catch (Exception e) {
            log.warn("转换 MemoryMatch 失败", e);
            return null;
        }
    }
}

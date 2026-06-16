package cn.chyuan.ai.domain.memory.retrieval;

import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import cn.chyuan.ai.domain.memory.adapter.port.IKeywordSearchPort;
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
    private final IKeywordSearchPort keywordSearchPort;
    
    @Override
    public List<MemoryMatch> recallByKeywords(String query, RecallOptions options) {
        log.info("ES 关键词检索：query={}, limit={}", query, options.getLimit());
        
        try {
            List<MemoryMatch> results = keywordSearchPort.search(query, options.getLimit());
            
            return results.stream()
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
        
        List<MemoryMatch> vectorResults = memoryService.recall(query, options);
        List<MemoryMatch> keywordResults = recallByKeywords(query, options);
        
        return rrfFusion(vectorResults, keywordResults, options);
    }
    
    @Override
    public List<MemoryMatch> multiPathRecall(String query, RecallOptions options) {
        log.info("多路检索（向量 + 关键词 + 知识图谱）: query={}", query);
        
        List<MemoryMatch> vectorResults = memoryService.recall(query, options);
        List<MemoryMatch> keywordResults = recallByKeywords(query, options);
        
        // TODO: 集成知识图谱检索
        List<MemoryMatch> graphResults = List.of();
        
        return rrfFusionThreeWay(vectorResults, keywordResults, graphResults, options);
    }
    
    @Override
    public void syncToElasticsearch(String memoryId) {
        log.info("同步记忆到 ES: memoryId={}", memoryId);
        keywordSearchPort.syncDocument(memoryId);
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
        keywordSearchPort.removeDocument(memoryId);
    }
    
    @Override
    public void rebuildIndex(String userId, String agentId) {
        log.info("重建 ES 索引：userId={}, agentId={}", userId, agentId);
        keywordSearchPort.rebuildIndex(userId, agentId);
    }
    
    private List<MemoryMatch> rrfFusion(List<MemoryMatch> vectorResults,
                                         List<MemoryMatch> keywordResults,
                                         RecallOptions options) {
        int k = 60;
        
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, MemoryMatch> matchMap = new HashMap<>();
        
        for (int i = 0; i < vectorResults.size(); i++) {
            MemoryMatch match = vectorResults.get(i);
            String id = match.getEntry().getMemoryId();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(id, rrfScore, Double::sum);
            matchMap.putIfAbsent(id, match);
        }
        
        for (int i = 0; i < keywordResults.size(); i++) {
            MemoryMatch match = keywordResults.get(i);
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
    
    private List<MemoryMatch> rrfFusionThreeWay(List<MemoryMatch> vectorResults,
                                                 List<MemoryMatch> keywordResults,
                                                 List<MemoryMatch> graphResults,
                                                 RecallOptions options) {
        int k = 60;
        
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, MemoryMatch> matchMap = new HashMap<>();
        
        for (int i = 0; i < vectorResults.size(); i++) {
            MemoryMatch match = vectorResults.get(i);
            String id = match.getEntry().getMemoryId();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(id, rrfScore, Double::sum);
            matchMap.putIfAbsent(id, match);
        }
        
        for (int i = 0; i < keywordResults.size(); i++) {
            MemoryMatch match = keywordResults.get(i);
            String id = match.getEntry().getMemoryId();
            double rrfScore = 1.0 / (k + i + 1);
            scoreMap.merge(id, rrfScore, Double::sum);
            matchMap.putIfAbsent(id, match);
        }
        
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
}

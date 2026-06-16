package cn.chyuan.ai.domain.memory.retrieval;

import cn.chyuan.ai.domain.memory.entity.EntityGraph;
import cn.chyuan.ai.domain.memory.entity.graph.KnowledgeGraphService;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 混合检索服务
 * 
 * 结合向量检索（语义相似度）和知识图谱查询（关系推理），
 * 向量检索负责模糊语义召回，知识图谱负责精确关系推理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {
    
    private final AgentMemoryService memoryService;
    private final KnowledgeGraphService knowledgeGraphService;
    
    /**
     * 执行混合检索
     *
     * @param query   查询内容
     * @param options 检索选项
     * @return 合并后的检索结果
     */
    public List<MemoryMatch> hybridRecall(String query, RecallOptions options) {
        // Step 1: 向量检索（语义召回）
        List<MemoryMatch> vectorResults = memoryService.recall(query, options);
        
        // Step 2: 知识图谱查询（关系推理）
        List<MemoryMatch> graphResults = List.of();
        try {
            graphResults = recallFromKnowledgeGraph(query, options);
        } catch (Exception e) {
            log.warn("知识图谱检索失败，降级为纯向量检索: {}", e.getMessage());
        }
        
        // Step 3: 合并结果（去重 + 排序）
        return mergeResults(vectorResults, graphResults, options);
    }
    
    private List<MemoryMatch> recallFromKnowledgeGraph(String query, RecallOptions options) {
        // 从查询中提取关键词，在知识图谱中查找相关实体
        // 然后通过实体的关联信息补充检索结果
        // 这里简化实现：直接用 query 作为实体名查找
        // 实际应该先做实体识别
        
        // TODO: 实现基于实体识别的知识图谱检索
        // 目前返回空列表，后续集成实体提取服务后完善
        return List.of();
    }
    
    private List<MemoryMatch> mergeResults(List<MemoryMatch> vectorResults,
                                            List<MemoryMatch> graphResults,
                                            RecallOptions options) {
        // 使用 Map 去重（以 memoryId 为 key）
        java.util.Map<String, MemoryMatch> merged = new java.util.LinkedHashMap<>();
        
        // 先加入向量检索结果
        for (MemoryMatch match : vectorResults) {
            merged.put(match.getEntry().getMemoryId(), match);
        }
        
        // 再加入知识图谱结果（如果已存在则取更高分）
        for (MemoryMatch match : graphResults) {
            String id = match.getEntry().getMemoryId();
            MemoryMatch existing = merged.get(id);
            if (existing == null || match.getScore() > existing.getScore()) {
                merged.put(id, match);
            }
        }
        
        // 按分数排序，取 top N
        return merged.values().stream()
            .sorted(Comparator.comparingDouble(MemoryMatch::getScore).reversed())
            .limit(options.getLimit())
            .collect(Collectors.toList());
    }
}

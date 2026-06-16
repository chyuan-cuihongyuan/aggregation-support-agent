package cn.chyuan.ai.domain.memory.longterm.abstraction;

import cn.chyuan.ai.domain.memory.adapter.port.ILlmGateway;
import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆抽象提炼服务
 * 
 * 将多条情节记忆（EPISODE）提炼为语义记忆（SEMANTIC）。
 * 本质是从具体经历中发现共同模式，升华为通用知识规律。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryAbstractionService {
    
    private final ILlmGateway llmGateway;
    
    /** 最少需要多少条情节记忆才能触发提炼 */
    private static final int MIN_EPISODES_FOR_ABSTRACTION = 3;
    
    /** 置信度阈值，低于此值不自动提炼 */
    private static final double CONFIDENCE_THRESHOLD = 0.7;
    
    private static final String ABSTRACTION_PROMPT = """
        请分析以下多条记忆，提炼出共同的规律、模式或通用知识。
        
        要求：
        1. 如果发现共同模式，返回JSON格式：{"semantic": "提炼的规律", "confidence": 0.0-1.0, "reason": "提炼原因"}
        2. 如果没有发现明显模式，返回：{"semantic": "NO_PATTERN", "confidence": 0.0, "reason": "原因"}
        3. confidence 表示你对提炼结果的信心程度
        
        记忆列表：
        %s
        """;
    
    /**
     * 对一组情节记忆执行抽象提炼
     *
     * @param episodes 情节记忆列表
     * @return 提炼结果
     */
    public AbstractionResult abstractEpisodes(List<AgentMemoryEntity> episodes) {
        if (episodes.size() < MIN_EPISODES_FOR_ABSTRACTION) {
            log.debug("情节记忆数量不足，跳过提炼: count={}", episodes.size());
            return AbstractionResult.builder()
                .success(false)
                .reason("情节记忆数量不足（需要至少" + MIN_EPISODES_FOR_ABSTRACTION + "条）")
                .build();
        }
        
        try {
            // 构建输入
            String episodesText = episodes.stream()
                .map(e -> "- " + e.getContent())
                .collect(Collectors.joining("\n"));
            
            // 调用 LLM 提炼
            String prompt = ABSTRACTION_PROMPT.formatted(episodesText);
            String response = llmGateway.call(prompt);
            
            // 解析结果
            Map<String, Object> result = parseJsonResponse(response);
            String semantic = (String) result.get("semantic");
            Double confidence = ((Number) result.get("confidence")).doubleValue();
            String reason = (String) result.get("reason");
            
            // 检查是否发现模式
            if ("NO_PATTERN".equals(semantic) || confidence < CONFIDENCE_THRESHOLD) {
                log.info("抽象提炼未达标: confidence={}, reason={}", confidence, reason);
                return AbstractionResult.builder()
                    .success(false)
                    .confidence(confidence)
                    .reason(reason)
                    .build();
            }
            
            List<String> sourceIds = episodes.stream()
                .map(AgentMemoryEntity::getMemoryId)
                .toList();
            
            log.info("抽象提炼成功: semantic='{}', confidence={}, sources={}",
                semantic, confidence, sourceIds.size());
            
            return AbstractionResult.builder()
                .semanticContent(semantic)
                .sourceEpisodeIds(sourceIds)
                .confidence(confidence)
                .reason(reason)
                .success(true)
                .build();
                
        } catch (Exception e) {
            log.error("抽象提炼失败", e);
            return AbstractionResult.builder()
                .success(false)
                .reason("LLM 调用失败: " + e.getMessage())
                .build();
        }
    }
    
    /**
     * 解析 LLM 返回的 JSON 响应
     */
    private Map<String, Object> parseJsonResponse(String response) {
        try {
            // 简单的 JSON 解析，实际应该使用 Jackson 或 Gson
            Map<String, Object> result = new HashMap<>();
            
            // 提取 semantic
            int semanticStart = response.indexOf("\"semantic\"") + 11;
            int semanticEnd = response.indexOf("\"", semanticStart);
            result.put("semantic", response.substring(semanticStart, semanticEnd));
            
            // 提取 confidence
            int confStart = response.indexOf("\"confidence\"") + 13;
            int confEnd = response.indexOf(",", confStart);
            if (confEnd == -1) confEnd = response.indexOf("}", confStart);
            result.put("confidence", Double.parseDouble(response.substring(confStart, confEnd).trim()));
            
            // 提取 reason
            int reasonStart = response.indexOf("\"reason\"") + 10;
            int reasonEnd = response.indexOf("\"", reasonStart);
            result.put("reason", response.substring(reasonStart, reasonEnd));
            
            return result;
        } catch (Exception e) {
            log.warn("解析 LLM 响应失败: {}", response, e);
            return Map.of("semantic", "NO_PATTERN", "confidence", 0.0, "reason", "解析失败");
        }
    }
    
    /**
     * 按主题对情节记忆分组
     *
     * @param episodes 情节记忆列表
     * @return 分组后的情节记忆（key=主题, value=情节列表）
     */
    public Map<String, List<AgentMemoryEntity>> clusterByTopic(List<AgentMemoryEntity> episodes) {
        // 简单分组策略：按 scope 前缀分组（同一项目/领域的记忆归为一组）
        return episodes.stream()
            .collect(Collectors.groupingBy(
                e -> extractTopicFromScope(e.getScope())
            ));
    }
    
    private String extractTopicFromScope(String scope) {
        if (scope == null || scope.equals("/")) {
            return "general";
        }
        // 取 scope 的第二级作为主题
        String[] parts = scope.split("/");
        return parts.length > 1 ? parts[1] : "general";
    }
}

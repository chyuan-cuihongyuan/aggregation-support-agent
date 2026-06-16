package cn.chyuan.ai.domain.memory.compression;

import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import cn.chyuan.ai.domain.memory.adapter.port.ILlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能记忆压缩服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryCompressionServiceImpl implements IMemoryCompressionService {
    
    private final AgentMemoryService memoryService;
    private final ILlmGateway llmGateway;
    
    @Override
    public CompressionResult compressMemories(List<AgentMemoryEntity> memories, String userId, String agentId) {
        log.info("自动压缩记忆：count={}, userId={}, agentId={}", memories.size(), userId, agentId);
        
        if (memories.size() < 5) {
            return compressBySummary(memories, userId, agentId);
        } else if (memories.size() < 20) {
            return compressByClustering(memories, userId, agentId);
        } else {
            return compressByAbstraction(memories, userId, agentId);
        }
    }
    
    @Override
    public CompressionResult compressBySummary(List<AgentMemoryEntity> memories, String userId, String agentId) {
        log.info("摘要压缩记忆：count={}", memories.size());
        long startTime = System.currentTimeMillis();
        
        try {
            String memoryContents = memories.stream()
                .map(AgentMemoryEntity::getContent)
                .collect(Collectors.joining("\n- ", "- ", ""));
            
            String prompt = String.format("""
                请将以下记忆内容压缩为一段简洁的摘要，保留关键信息：
                
                %s
                
                要求：
                1. 摘要不超过 200 字
                2. 保留关键事实、偏好、决策
                3. 去除冗余和重复信息
                4. 使用第一人称（"我"）
                
                请直接返回摘要内容：
                """, memoryContents);
            
            String summary = llmGateway.call(prompt);
            
            String compressedId = UUID.randomUUID().toString();
            memoryService.remember(
                summary,
                MemoryOptions.builder()
                    .tenantId(memories.get(0).getTenantId())
                    .userId(userId)
                    .agentId(agentId)
                    .memoryType(MemoryType.SEMANTIC)
                    .scope("/compressed/summary")
                    .source("compression")
                    .build()
            );
            
            for (AgentMemoryEntity memory : memories) {
                memoryService.forget(memory.getMemoryId());
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            return CompressionResult.builder()
                .compressedMemoryId(compressedId)
                .originalCount(memories.size())
                .compressedCount(1)
                .compressionRatio(memories.size())
                .compressedContent(summary)
                .compressionStrategy("SUMMARY")
                .durationMs(duration)
                .success(true)
                .build();
                
        } catch (Exception e) {
            log.error("摘要压缩失败", e);
            return CompressionResult.builder()
                .originalCount(memories.size())
                .compressedCount(memories.size())
                .compressionRatio(1.0)
                .compressionStrategy("SUMMARY")
                .durationMs(System.currentTimeMillis() - startTime)
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }
    
    @Override
    public CompressionResult compressByClustering(List<AgentMemoryEntity> memories, String userId, String agentId) {
        log.info("聚类压缩记忆：count={}", memories.size());
        long startTime = System.currentTimeMillis();
        
        try {
            Map<String, List<AgentMemoryEntity>> clusters = clusterMemories(memories);
            
            List<String> clusterSummaries = new ArrayList<>();
            for (Map.Entry<String, List<AgentMemoryEntity>> entry : clusters.entrySet()) {
                String clusterSummary = generateClusterSummary(entry.getKey(), entry.getValue());
                clusterSummaries.add(clusterSummary);
            }
            
            String finalContent = String.join("\n\n", clusterSummaries);
            
            String compressedId = UUID.randomUUID().toString();
            memoryService.remember(
                finalContent,
                MemoryOptions.builder()
                    .tenantId(memories.get(0).getTenantId())
                    .userId(userId)
                    .agentId(agentId)
                    .memoryType(MemoryType.SEMANTIC)
                    .scope("/compressed/cluster")
                    .source("compression")
                    .build()
            );
            
            for (AgentMemoryEntity memory : memories) {
                memoryService.forget(memory.getMemoryId());
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            return CompressionResult.builder()
                .compressedMemoryId(compressedId)
                .originalCount(memories.size())
                .compressedCount(clusterSummaries.size())
                .compressionRatio((double) memories.size() / clusterSummaries.size())
                .compressedContent(finalContent)
                .compressionStrategy("CLUSTER")
                .durationMs(duration)
                .success(true)
                .build();
                
        } catch (Exception e) {
            log.error("聚类压缩失败", e);
            return CompressionResult.builder()
                .originalCount(memories.size())
                .compressedCount(memories.size())
                .compressionRatio(1.0)
                .compressionStrategy("CLUSTER")
                .durationMs(System.currentTimeMillis() - startTime)
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }
    
    @Override
    public CompressionResult compressByAbstraction(List<AgentMemoryEntity> memories, String userId, String agentId) {
        log.info("抽象压缩记忆：count={}", memories.size());
        long startTime = System.currentTimeMillis();
        
        try {
            String memoryContents = memories.stream()
                .map(AgentMemoryEntity::getContent)
                .collect(Collectors.joining("\n- ", "- ", ""));
            
            String prompt = String.format("""
                请从以下记忆中提取关键事实、规则和模式，形成结构化的知识：
                
                %s
                
                要求：
                1. 提取 3-5 个核心要点
                2. 每个要点用一句话概括
                3. 使用 JSON 数组格式返回
                4. 格式：["要点1", "要点2", ...]
                
                请直接返回 JSON：
                """, memoryContents);
            
            String abstractions = llmGateway.call(prompt);
            
            String compressedId = UUID.randomUUID().toString();
            memoryService.remember(
                abstractions,
                MemoryOptions.builder()
                    .tenantId(memories.get(0).getTenantId())
                    .userId(userId)
                    .agentId(agentId)
                    .memoryType(MemoryType.SEMANTIC)
                    .scope("/compressed/abstract")
                    .source("compression")
                    .build()
            );
            
            for (AgentMemoryEntity memory : memories) {
                memoryService.forget(memory.getMemoryId());
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            return CompressionResult.builder()
                .compressedMemoryId(compressedId)
                .originalCount(memories.size())
                .compressedCount(1)
                .compressionRatio(memories.size())
                .compressedContent(abstractions)
                .compressionStrategy("ABSTRACT")
                .durationMs(duration)
                .success(true)
                .build();
                
        } catch (Exception e) {
            log.error("抽象压缩失败", e);
            return CompressionResult.builder()
                .originalCount(memories.size())
                .compressedCount(memories.size())
                .compressionRatio(1.0)
                .compressionStrategy("ABSTRACT")
                .durationMs(System.currentTimeMillis() - startTime)
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }
    
    @Override
    public CompressionResult compressOldMemories(String userId, String agentId, int daysAgo, double targetRatio) {
        log.info("压缩旧记忆：daysAgo={}, targetRatio={}", daysAgo, targetRatio);
        
        Instant cutoff = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        List<AgentMemoryEntity> oldMemories = memoryService.getOldMemories(userId, agentId, cutoff);
        
        if (oldMemories.isEmpty()) {
            return CompressionResult.builder()
                .originalCount(0)
                .compressedCount(0)
                .compressionRatio(1.0)
                .compressionStrategy("OLD")
                .success(true)
                .build();
        }
        
        int targetCount = (int) (oldMemories.size() / targetRatio);
        List<AgentMemoryEntity> toCompress = oldMemories.subList(0, Math.min(oldMemories.size(), targetCount * 2));
        
        return compressMemories(toCompress, userId, agentId);
    }
    
    @Override
    public CompressionSuggestion getCompressionSuggestion(String userId, String agentId) {
        log.info("获取压缩建议：userId={}, agentId={}", userId, agentId);
        
        List<AgentMemoryEntity> allMemories = memoryService.getAllMemories(userId, agentId);
        
        if (allMemories.size() < 10) {
            return CompressionSuggestion.builder()
                .shouldCompress(false)
                .reason("记忆数量较少，无需压缩")
                .build();
        }
        
        long oldCount = allMemories.stream()
            .filter(m -> m.getCreatedAt().isBefore(Instant.now().minus(30, ChronoUnit.DAYS)))
            .count();
        
        if (oldCount > allMemories.size() * 0.5) {
            return CompressionSuggestion.builder()
                .shouldCompress(true)
                .recommendedStrategy("CLUSTER")
                .estimatedRatio(3.0)
                .reason("超过 50% 的记忆超过 30 天，建议聚类压缩")
                .build();
        }
        
        if (allMemories.size() > 50) {
            return CompressionSuggestion.builder()
                .shouldCompress(true)
                .recommendedStrategy("ABSTRACT")
                .estimatedRatio(5.0)
                .reason("记忆数量过多，建议抽象压缩")
                .build();
        }
        
        return CompressionSuggestion.builder()
            .shouldCompress(false)
            .reason("记忆状态良好")
            .build();
    }
    
    private Map<String, List<AgentMemoryEntity>> clusterMemories(List<AgentMemoryEntity> memories) {
        Map<String, List<AgentMemoryEntity>> clusters = new HashMap<>();
        for (AgentMemoryEntity memory : memories) {
            String topic = extractTopic(memory.getContent());
            clusters.computeIfAbsent(topic, k -> new ArrayList<>()).add(memory);
        }
        return clusters;
    }
    
    private String extractTopic(String content) {
        return content.substring(0, Math.min(10, content.length()));
    }
    
    private String generateClusterSummary(String topic, List<AgentMemoryEntity> memories) {
        String contents = memories.stream()
            .map(AgentMemoryEntity::getContent)
            .collect(Collectors.joining("\n- ", "- ", ""));
        
        String prompt = String.format("""
            请将以下关于"%s"的记忆压缩为一段简洁的摘要：
            
            %s
            
            要求：
            1. 摘要不超过 100 字
            2. 保留关键信息
            3. 使用第一人称
            
            请直接返回摘要：
            """, topic, contents);
        
        return llmGateway.call(prompt);
    }
}

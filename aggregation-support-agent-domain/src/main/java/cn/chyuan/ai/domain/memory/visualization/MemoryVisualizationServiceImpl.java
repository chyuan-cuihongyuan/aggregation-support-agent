package cn.chyuan.ai.domain.memory.visualization;

import cn.chyuan.ai.domain.memory.entity.AgentEntityMemoryEntity;
import cn.chyuan.ai.domain.memory.entity.graph.KnowledgeGraphService;
import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆可视化服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryVisualizationServiceImpl implements IMemoryVisualizationService {
    
    private final AgentMemoryService memoryService;
    private final KnowledgeGraphService knowledgeGraphService;
    
    @Override
    public MemoryGraphVisualization getMemoryGraph(String userId, String agentId, int maxHops) {
        log.info("获取记忆图谱：userId={}, agentId={}, maxHops={}", userId, agentId, maxHops);
        
        try {
            List<AgentEntityMemoryEntity> entities = knowledgeGraphService.getAllEntities(userId, agentId);
            
            List<MemoryGraphVisualization.GraphNode> nodes = entities.stream()
                .map(entity -> MemoryGraphVisualization.GraphNode.builder()
                    .id(entity.getEntityId())
                    .label(entity.getEntityName())
                    .type(entity.getEntityType().name())
                    .properties(entity.getAttributes())
                    .size(calculateNodeSize(entity))
                    .color(getNodeColor(entity.getEntityType().name()))
                    .build())
                .collect(Collectors.toList());
            
            List<MemoryGraphVisualization.GraphEdge> edges = new ArrayList<>();
            for (AgentEntityMemoryEntity entity : entities) {
                List<AgentEntityMemoryEntity> relatedEntities = 
                    knowledgeGraphService.getRelatedEntities(entity.getEntityId(), userId, agentId, maxHops);
                
                for (AgentEntityMemoryEntity related : relatedEntities) {
                    edges.add(MemoryGraphVisualization.GraphEdge.builder()
                        .id(entity.getEntityId() + "->" + related.getEntityId())
                        .source(entity.getEntityId())
                        .target(related.getEntityId())
                        .relationType("RELATED_TO")
                        .weight(1.0)
                        .build());
                }
            }
            
            MemoryGraphVisualization.GraphStats stats = calculateGraphStats(nodes, edges);
            
            return MemoryGraphVisualization.builder()
                .nodes(nodes)
                .edges(edges)
                .stats(stats)
                .build();
                
        } catch (Exception e) {
            log.error("获取记忆图谱失败", e);
            return MemoryGraphVisualization.builder()
                .nodes(List.of())
                .edges(List.of())
                .stats(MemoryGraphVisualization.GraphStats.builder()
                    .nodeCount(0)
                    .edgeCount(0)
                    .build())
                .build();
        }
    }
    
    @Override
    public MemoryTimeline getMemoryTimeline(String userId, String agentId, Instant startTime, Instant endTime) {
        log.info("获取记忆时间线：userId={}, agentId={}", userId, agentId);
        
        try {
            List<AgentMemoryEntity> memories = memoryService.getAllMemories(userId, agentId);
            
            List<AgentMemoryEntity> filtered = memories.stream()
                .filter(m -> m.getCreatedAt().isAfter(startTime) && m.getCreatedAt().isBefore(endTime))
                .sorted(Comparator.comparing(AgentMemoryEntity::getCreatedAt))
                .collect(Collectors.toList());
            
            List<MemoryTimeline.TimelineEvent> events = filtered.stream()
                .map(memory -> MemoryTimeline.TimelineEvent.builder()
                    .id(memory.getMemoryId())
                    .timestamp(memory.getCreatedAt())
                    .eventType("MEMORY_STORE")
                    .description("存储记忆：" + memory.getContent().substring(0, Math.min(50, memory.getContent().length())))
                    .memoryId(memory.getMemoryId())
                    .memoryType(memory.getMemoryType().name())
                    .contentSummary(memory.getContent().substring(0, Math.min(100, memory.getContent().length())))
                    .build())
                .collect(Collectors.toList());
            
            MemoryTimeline.TimelineStats stats = calculateTimelineStats(events);
            
            return MemoryTimeline.builder()
                .events(events)
                .stats(stats)
                .build();
                
        } catch (Exception e) {
            log.error("获取记忆时间线失败", e);
            return MemoryTimeline.builder()
                .events(List.of())
                .stats(MemoryTimeline.TimelineStats.builder()
                    .totalEvents(0)
                    .build())
                .build();
        }
    }
    
    @Override
    public MemoryStatistics getMemoryStatistics(String userId, String agentId) {
        log.info("获取记忆统计：userId={}, agentId={}", userId, agentId);
        
        try {
            List<AgentMemoryEntity> memories = memoryService.getAllMemories(userId, agentId);
            
            MemoryStatistics.OverallStats overall = calculateOverallStats(memories);
            MemoryStatistics.LayerStats layerStats = calculateLayerStats();
            MemoryStatistics.TrendData trends = calculateTrends(memories);
            
            return MemoryStatistics.builder()
                .overall(overall)
                .layerStats(layerStats)
                .trends(trends)
                .build();
                
        } catch (Exception e) {
            log.error("获取记忆统计失败", e);
            return MemoryStatistics.builder()
                .overall(MemoryStatistics.OverallStats.builder()
                    .totalMemories(0L)
                    .build())
                .build();
        }
    }
    
    @Override
    public List<MemoryStatistics.DailyCount> getMemoryHeatmap(String userId, String agentId, int days) {
        log.info("获取记忆热度图：userId={}, agentId={}, days={}", userId, agentId, days);
        
        List<AgentMemoryEntity> memories = memoryService.getAllMemories(userId, agentId);
        
        Map<String, Long> dailyCounts = memories.stream()
            .filter(m -> m.getCreatedAt().isAfter(Instant.now().minus(days, ChronoUnit.DAYS)))
            .collect(Collectors.groupingBy(
                m -> LocalDate.ofInstant(m.getCreatedAt(), ZoneId.systemDefault()).toString(),
                Collectors.counting()
            ));
        
        return dailyCounts.entrySet().stream()
            .map(entry -> MemoryStatistics.DailyCount.builder()
                .date(entry.getKey())
                .count(entry.getValue())
                .build())
            .sorted(Comparator.comparing(MemoryStatistics.DailyCount::getDate))
            .collect(Collectors.toList());
    }
    
    @Override
    public Map<String, Long> getMemoryTypeDistribution(String userId, String agentId) {
        List<AgentMemoryEntity> memories = memoryService.getAllMemories(userId, agentId);
        
        return memories.stream()
            .collect(Collectors.groupingBy(
                m -> m.getMemoryType().name(),
                Collectors.counting()
            ));
    }
    
    @Override
    public String exportMemoryData(String userId, String agentId) {
        log.info("导出记忆数据：userId={}, agentId={}", userId, agentId);
        
        try {
            List<AgentMemoryEntity> memories = memoryService.getAllMemories(userId, agentId);
            
            Map<String, Object> exportData = new HashMap<>();
            exportData.put("userId", userId);
            exportData.put("agentId", agentId);
            exportData.put("exportTime", Instant.now().toString());
            exportData.put("memories", memories);
            exportData.put("total", memories.size());
            
            return JSON.toJSONString(exportData, true);
            
        } catch (Exception e) {
            log.error("导出记忆数据失败", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    private Double calculateNodeSize(AgentEntityMemoryEntity entity) {
        return 10.0 + (entity.getConfidence() * 20);
    }
    
    private String getNodeColor(String entityType) {
        return switch (entityType) {
            case "USER" -> "#4CAF50";
            case "AGENT" -> "#2196F3";
            case "CONCEPT" -> "#FF9800";
            case "TOOL" -> "#9C27B0";
            default -> "#757575";
        };
    }
    
    private MemoryGraphVisualization.GraphStats calculateGraphStats(
            List<MemoryGraphVisualization.GraphNode> nodes,
            List<MemoryGraphVisualization.GraphEdge> edges) {
        
        Map<String, Integer> nodeTypeDist = nodes.stream()
            .collect(Collectors.groupingBy(
                MemoryGraphVisualization.GraphNode::getType,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
        
        Map<String, Integer> relationTypeDist = edges.stream()
            .collect(Collectors.groupingBy(
                MemoryGraphVisualization.GraphEdge::getRelationType,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
        
        double avgDegree = edges.isEmpty() ? 0 : (2.0 * edges.size()) / nodes.size();
        
        return MemoryGraphVisualization.GraphStats.builder()
            .nodeCount(nodes.size())
            .edgeCount(edges.size())
            .nodeTypeDistribution(nodeTypeDist)
            .relationTypeDistribution(relationTypeDist)
            .avgDegree(avgDegree)
            .largestComponentSize(nodes.size())
            .build();
    }
    
    private MemoryTimeline.TimelineStats calculateTimelineStats(List<MemoryTimeline.TimelineEvent> events) {
        if (events.isEmpty()) {
            return MemoryTimeline.TimelineStats.builder()
                .totalEvents(0)
                .build();
        }
        
        Map<String, Integer> eventTypeDist = events.stream()
            .collect(Collectors.groupingBy(
                MemoryTimeline.TimelineEvent::getEventType,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
        
        Map<String, Integer> memoryTypeDist = events.stream()
            .filter(e -> e.getMemoryType() != null)
            .collect(Collectors.groupingBy(
                MemoryTimeline.TimelineEvent::getMemoryType,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
        
        long timeRangeDays = ChronoUnit.DAYS.between(
            events.get(0).getTimestamp(),
            events.get(events.size() - 1).getTimestamp()
        );
        
        double avgEventsPerDay = timeRangeDays > 0 ? (double) events.size() / timeRangeDays : events.size();
        
        return MemoryTimeline.TimelineStats.builder()
            .totalEvents(events.size())
            .timeRangeDays((int) timeRangeDays)
            .eventTypeDistribution(eventTypeDist)
            .memoryTypeDistribution(memoryTypeDist)
            .avgEventsPerDay(avgEventsPerDay)
            .mostActiveDate(findMostActiveDate(events))
            .build();
    }
    
    private String findMostActiveDate(List<MemoryTimeline.TimelineEvent> events) {
        return events.stream()
            .collect(Collectors.groupingBy(
                e -> LocalDate.ofInstant(e.getTimestamp(), ZoneId.systemDefault()).toString(),
                Collectors.counting()
            ))
            .entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse("");
    }
    
    private MemoryStatistics.OverallStats calculateOverallStats(List<AgentMemoryEntity> memories) {
        long totalSize = memories.stream()
            .mapToLong(m -> m.getContent().length() * 2L)
            .sum();
        
        double avgImportance = memories.stream()
            .mapToDouble(m -> m.getImportance().doubleValue())
            .average()
            .orElse(0);
        
        Map<String, Long> typeDist = memories.stream()
            .collect(Collectors.groupingBy(
                m -> m.getMemoryType().name(),
                Collectors.counting()
            ));
        
        Map<String, Long> sourceDist = memories.stream()
            .filter(m -> m.getSource() != null)
            .collect(Collectors.groupingBy(
                AgentMemoryEntity::getSource,
                Collectors.counting()
            ));
        
        return MemoryStatistics.OverallStats.builder()
            .totalMemories((long) memories.size())
            .totalSizeBytes(totalSize)
            .avgImportance(avgImportance)
            .memoryTypeDistribution(typeDist)
            .sourceDistribution(sourceDist)
            .build();
    }
    
    private MemoryStatistics.LayerStats calculateLayerStats() {
        return MemoryStatistics.LayerStats.builder()
            .sensory(MemoryStatistics.SensoryStats.builder()
                .processedCount(0L).filteredCount(0L).filterRate(0.0).avgProcessingTimeMs(0.0).build())
            .shortTerm(MemoryStatistics.ShortTermStats.builder()
                .activeSessions(0).avgMessagesPerSession(0.0).compressionCount(0L).offloadCount(0L).avgContextWindowUsage(0.0).build())
            .longTerm(MemoryStatistics.LongTermStats.builder()
                .episodicCount(0L).semanticCount(0L).abstractionCount(0L).avgRecallTimeMs(0.0).recallHitRate(0.0).build())
            .entity(MemoryStatistics.EntityStats.builder()
                .entityCount(0L).relationCount(0L).avgQueryTimeMs(0.0).build())
            .build();
    }
    
    private MemoryStatistics.TrendData calculateTrends(List<AgentMemoryEntity> memories) {
        List<MemoryStatistics.DailyCount> dailyStorage = memories.stream()
            .filter(m -> m.getCreatedAt().isAfter(Instant.now().minus(30, ChronoUnit.DAYS)))
            .collect(Collectors.groupingBy(
                m -> LocalDate.ofInstant(m.getCreatedAt(), ZoneId.systemDefault()).toString(),
                Collectors.counting()
            ))
            .entrySet().stream()
            .map(entry -> MemoryStatistics.DailyCount.builder()
                .date(entry.getKey())
                .count(entry.getValue())
                .build())
            .sorted(Comparator.comparing(MemoryStatistics.DailyCount::getDate))
            .collect(Collectors.toList());
        
        return MemoryStatistics.TrendData.builder()
            .dailyStorageCounts(dailyStorage)
            .dailyRecallCounts(List.of())
            .dailyConsolidationCounts(List.of())
            .build();
    }
}

package cn.chyuan.ai.domain.memory.visualization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 记忆时间线可视化数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryTimeline {
    
    /**
     * 时间线事件列表
     */
    private List<TimelineEvent> events;
    
    /**
     * 统计信息
     */
    private TimelineStats stats;
    
    /**
     * 时间线事件
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineEvent {
        /**
         * 事件 ID
         */
        private String id;
        
        /**
         * 事件时间
         */
        private Instant timestamp;
        
        /**
         * 事件类型（MEMORY_STORE / MEMORY_RECALL / MEMORY_COMPRESS / MEMORY_CONSOLIDATE）
         */
        private String eventType;
        
        /**
         * 事件描述
         */
        private String description;
        
        /**
         * 记忆 ID
         */
        private String memoryId;
        
        /**
         * 记忆类型
         */
        private String memoryType;
        
        /**
         * 记忆内容（摘要）
         */
        private String contentSummary;
        
        /**
         * 事件属性
         */
        private Map<String, Object> properties;
    }
    
    /**
     * 时间线统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineStats {
        /**
         * 总事件数
         */
        private Integer totalEvents;
        
        /**
         * 时间范围（天）
         */
        private Integer timeRangeDays;
        
        /**
         * 事件类型分布
         */
        private Map<String, Integer> eventTypeDistribution;
        
        /**
         * 记忆类型分布
         */
        private Map<String, Integer> memoryTypeDistribution;
        
        /**
         * 每日平均事件数
         */
        private Double avgEventsPerDay;
        
        /**
         * 最活跃日期
         */
        private String mostActiveDate;
    }
}

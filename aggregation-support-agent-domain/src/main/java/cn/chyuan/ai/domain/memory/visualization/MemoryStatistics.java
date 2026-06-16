package cn.chyuan.ai.domain.memory.visualization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 记忆统计可视化数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryStatistics {
    
    /**
     * 总体统计
     */
    private OverallStats overall;
    
    /**
     * 各层统计
     */
    private LayerStats layerStats;
    
    /**
     * 趋势数据
     */
    private TrendData trends;
    
    /**
     * 总体统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverallStats {
        /**
         * 总记忆数
         */
        private Long totalMemories;
        
        /**
         * 总存储大小（字节）
         */
        private Long totalSizeBytes;
        
        /**
         * 平均重要性
         */
        private Double avgImportance;
        
        /**
         * 记忆类型分布
         */
        private Map<String, Long> memoryTypeDistribution;
        
        /**
         * 来源分布
         */
        private Map<String, Long> sourceDistribution;
    }
    
    /**
     * 各层统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LayerStats {
        /**
         * 感知记忆层统计
         */
        private SensoryStats sensory;
        
        /**
         * 短期记忆层统计
         */
        private ShortTermStats shortTerm;
        
        /**
         * 长期记忆层统计
         */
        private LongTermStats longTerm;
        
        /**
         * 实体记忆层统计
         */
        private EntityStats entity;
    }
    
    /**
     * 感知记忆层统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SensoryStats {
        /**
         * 处理总数
         */
        private Long processedCount;
        
        /**
         * 过滤数
         */
        private Long filteredCount;
        
        /**
         * 过滤率
         */
        private Double filterRate;
        
        /**
         * 平均处理时间（毫秒）
         */
        private Double avgProcessingTimeMs;
    }
    
    /**
     * 短期记忆层统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShortTermStats {
        /**
         * 当前活跃会话数
         */
        private Integer activeSessions;
        
        /**
         * 平均消息数/会话
         */
        private Double avgMessagesPerSession;
        
        /**
         * 压缩次数
         */
        private Long compressionCount;
        
        /**
         * 卸载次数
         */
        private Long offloadCount;
        
        /**
         * 平均 Context Window 使用率
         */
        private Double avgContextWindowUsage;
    }
    
    /**
     * 长期记忆层统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LongTermStats {
        /**
         * 情节记忆数
         */
        private Long episodicCount;
        
        /**
         * 语义记忆数
         */
        private Long semanticCount;
        
        /**
         * 抽象提炼次数
         */
        private Long abstractionCount;
        
        /**
         * 平均检索时间（毫秒）
         */
        private Double avgRecallTimeMs;
        
        /**
         * 检索命中率
         */
        private Double recallHitRate;
    }
    
    /**
     * 实体记忆层统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityStats {
        /**
         * 实体总数
         */
        private Long entityCount;
        
        /**
         * 关系总数
         */
        private Long relationCount;
        
        /**
         * 实体类型分布
         */
        private Map<String, Long> entityTypeDistribution;
        
        /**
         * 平均查询时间（毫秒）
         */
        private Double avgQueryTimeMs;
    }
    
    /**
     * 趋势数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendData {
        /**
         * 每日记忆存储数（最近 30 天）
         */
        private List<DailyCount> dailyStorageCounts;
        
        /**
         * 每日检索次数（最近 30 天）
         */
        private List<DailyCount> dailyRecallCounts;
        
        /**
         * 每日整合次数（最近 30 天）
         */
        private List<DailyCount> dailyConsolidationCounts;
    }
    
    /**
     * 每日计数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyCount {
        /**
         * 日期
         */
        private String date;
        
        /**
         * 计数
         */
        private Long count;
    }
}

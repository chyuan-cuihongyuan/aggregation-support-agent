package cn.chyuan.ai.domain.memory.abtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * A/B 测试配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ABTestConfig {
    
    /**
     * 测试 ID
     */
    private String testId;
    
    /**
     * 测试名称
     */
    private String testName;
    
    /**
     * 测试描述
     */
    private String description;
    
    /**
     * 实验组配置（组名 -> 权重配置）
     */
    private Map<String, ScoringWeights> experimentGroups;
    
    /**
     * 流量分配（组名 -> 百分比，总和为 100）
     */
    private Map<String, Integer> trafficAllocation;
    
    /**
     * 开始时间
     */
    private Instant startTime;
    
    /**
     * 结束时间
     */
    private Instant endTime;
    
    /**
     * 是否启用
     */
    private boolean enabled;
    
    /**
     * 评分权重配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoringWeights {
        /**
         * 语义相似度权重
         */
        private double semanticWeight;
        
        /**
         * 时间衰减权重
         */
        private double recencyWeight;
        
        /**
         * 重要性权重
         */
        private double importanceWeight;
        
        /**
         * 时间衰减半衰期（天）
         */
        private int recencyHalfLifeDays;
    }
}

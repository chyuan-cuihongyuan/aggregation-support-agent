package cn.chyuan.ai.domain.memory.abtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * A/B 测试结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ABTestResult {
    
    /**
     * 测试 ID
     */
    private String testId;
    
    /**
     * 组名
     */
    private String groupName;
    
    /**
     * 用户 ID
     */
    private String userId;
    
    /**
     * 查询内容
     */
    private String query;
    
    /**
     * 检索结果数量
     */
    private int resultCount;
    
    /**
     * 平均相关性分数
     */
    private double avgRelevanceScore;
    
    /**
     * 用户满意度（1-5 分）
     */
    private Integer userSatisfaction;
    
    /**
     * 是否使用了记忆
     */
    private boolean memoryUsed;
    
    /**
     * 响应时间（毫秒）
     */
    private long responseTimeMs;
    
    /**
     * 记录时间
     */
    private Instant timestamp;
    
    /**
     * 额外指标
     */
    private Map<String, Object> metrics;
}

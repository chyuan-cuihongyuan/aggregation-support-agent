package cn.chyuan.ai.domain.memory.abtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A/B 测试服务接口
 * 
 * 支持对比不同评分权重配置的效果
 */
public interface IABTestService {
    
    /**
     * 创建 A/B 测试
     *
     * @param config 测试配置
     * @return 测试 ID
     */
    String createTest(ABTestConfig config);
    
    /**
     * 获取用户所属的实验组
     *
     * @param testId 测试 ID
     * @param userId 用户 ID
     * @return 组名
     */
    String getUserGroup(String testId, String userId);
    
    /**
     * 获取实验组的权重配置
     *
     * @param testId    测试 ID
     * @param groupName 组名
     * @return 权重配置
     */
    ABTestConfig.ScoringWeights getGroupWeights(String testId, String groupName);
    
    /**
     * 记录测试结果
     *
     * @param result 测试结果
     */
    void recordResult(ABTestResult result);
    
    /**
     * 批量记录测试结果
     *
     * @param results 测试结果列表
     */
    void recordResults(List<ABTestResult> results);
    
    /**
     * 获取测试报告
     *
     * @param testId 测试 ID
     * @return 测试报告
     */
    ABTestReport getTestReport(String testId);
    
    /**
     * 停止测试
     *
     * @param testId 测试 ID
     */
    void stopTest(String testId);
    
    /**
     * 删除测试
     *
     * @param testId 测试 ID
     */
    void deleteTest(String testId);
    
    /**
     * 列出所有测试
     *
     * @return 测试列表
     */
    List<ABTestConfig> listTests();
    
    /**
     * 测试报告
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ABTestReport {
        /**
         * 测试 ID
         */
        private String testId;
        
        /**
         * 测试名称
         */
        private String testName;
        
        /**
         * 总样本数
         */
        private int totalSamples;
        
        /**
         * 各组统计
         */
        private Map<String, GroupStats> groupStats;
        
        /**
         * 胜出组
         */
        private String winnerGroup;
        
        /**
         * 胜出原因
         */
        private String winnerReason;
        
        /**
         * 统计显著性（p-value）
         */
        private Double pValue;
        
        /**
         * 是否显著
         */
        private boolean significant;
    }
    
    /**
     * 组统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class GroupStats {
        /**
         * 样本数
         */
        private int sampleCount;
        
        /**
         * 平均相关性分数
         */
        private double avgRelevanceScore;
        
        /**
         * 平均用户满意度
         */
        private double avgSatisfaction;
        
        /**
         * 平均响应时间（毫秒）
         */
        private double avgResponseTimeMs;
        
        /**
         * 记忆使用率
         */
        private double memoryUsageRate;
    }
}

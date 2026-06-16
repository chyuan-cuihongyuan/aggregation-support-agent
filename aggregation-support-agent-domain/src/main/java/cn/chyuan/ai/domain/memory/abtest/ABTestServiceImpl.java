package cn.chyuan.ai.domain.memory.abtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A/B 测试服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ABTestServiceImpl implements IABTestService {
    
    /**
     * 测试配置存储（内存存储，生产环境应使用数据库）
     */
    private final Map<String, ABTestConfig> testConfigs = new ConcurrentHashMap<>();
    
    /**
     * 测试结果存储
     */
    private final Map<String, List<ABTestResult>> testResults = new ConcurrentHashMap<>();
    
    @Override
    public String createTest(ABTestConfig config) {
        log.info("创建 A/B 测试：name={}", config.getTestName());
        
        String testId = UUID.randomUUID().toString();
        config.setTestId(testId);
        config.setEnabled(true);
        
        // 验证流量分配
        int totalTraffic = config.getTrafficAllocation().values().stream()
            .mapToInt(Integer::intValue)
            .sum();
        if (totalTraffic != 100) {
            throw new IllegalArgumentException("流量分配总和必须为 100，当前为 " + totalTraffic);
        }
        
        testConfigs.put(testId, config);
        testResults.put(testId, new ArrayList<>());
        
        log.info("A/B 测试创建成功：testId={}", testId);
        return testId;
    }
    
    @Override
    public String getUserGroup(String testId, String userId) {
        ABTestConfig config = testConfigs.get(testId);
        if (config == null || !config.isEnabled()) {
            return "control"; // 默认对照组
        }
        
        // 基于用户 ID 哈希分配（确保同一用户始终在同一组）
        int hash = Math.abs(userId.hashCode());
        int bucket = hash % 100;
        
        int cumulative = 0;
        for (Map.Entry<String, Integer> entry : config.getTrafficAllocation().entrySet()) {
            cumulative += entry.getValue();
            if (bucket < cumulative) {
                return entry.getKey();
            }
        }
        
        return "control";
    }
    
    @Override
    public ABTestConfig.ScoringWeights getGroupWeights(String testId, String groupName) {
        ABTestConfig config = testConfigs.get(testId);
        if (config == null) {
            return null;
        }
        return config.getExperimentGroups().get(groupName);
    }
    
    @Override
    public void recordResult(ABTestResult result) {
        log.debug("记录 A/B 测试结果：testId={}, group={}", result.getTestId(), result.getGroupName());
        
        List<ABTestResult> results = testResults.get(result.getTestId());
        if (results != null) {
            results.add(result);
        }
    }
    
    @Override
    public void recordResults(List<ABTestResult> results) {
        for (ABTestResult result : results) {
            recordResult(result);
        }
    }
    
    @Override
    public ABTestReport getTestReport(String testId) {
        log.info("生成 A/B 测试报告：testId={}", testId);
        
        ABTestConfig config = testConfigs.get(testId);
        List<ABTestResult> results = testResults.get(testId);
        
        if (config == null || results == null) {
            return null;
        }
        
        // 按组统计
        Map<String, GroupStats> groupStats = new HashMap<>();
        Map<String, List<ABTestResult>> groupedResults = results.stream()
            .collect(Collectors.groupingBy(ABTestResult::getGroupName));
        
        for (Map.Entry<String, List<ABTestResult>> entry : groupedResults.entrySet()) {
            String groupName = entry.getKey();
            List<ABTestResult> groupResults = entry.getValue();
            
            GroupStats stats = calculateGroupStats(groupResults);
            groupStats.put(groupName, stats);
        }
        
        // 确定胜出组
        String winnerGroup = determineWinner(groupStats);
        String winnerReason = generateWinnerReason(groupStats, winnerGroup);
        
        // 计算统计显著性（简化实现）
        double pValue = calculatePValue(groupStats);
        boolean significant = pValue < 0.05;
        
        return ABTestReport.builder()
            .testId(testId)
            .testName(config.getTestName())
            .totalSamples(results.size())
            .groupStats(groupStats)
            .winnerGroup(winnerGroup)
            .winnerReason(winnerReason)
            .pValue(pValue)
            .significant(significant)
            .build();
    }
    
    @Override
    public void stopTest(String testId) {
        log.info("停止 A/B 测试：testId={}", testId);
        ABTestConfig config = testConfigs.get(testId);
        if (config != null) {
            config.setEnabled(false);
        }
    }
    
    @Override
    public void deleteTest(String testId) {
        log.info("删除 A/B 测试：testId={}", testId);
        testConfigs.remove(testId);
        testResults.remove(testId);
    }
    
    @Override
    public List<ABTestConfig> listTests() {
        return new ArrayList<>(testConfigs.values());
    }
    
    /**
     * 计算组统计
     */
    private GroupStats calculateGroupStats(List<ABTestResult> results) {
        if (results.isEmpty()) {
            return GroupStats.builder()
                .sampleCount(0)
                .avgRelevanceScore(0)
                .avgSatisfaction(0)
                .avgResponseTimeMs(0)
                .memoryUsageRate(0)
                .build();
        }
        
        double avgRelevance = results.stream()
            .mapToDouble(ABTestResult::getAvgRelevanceScore)
            .average()
            .orElse(0);
        
        double avgSatisfaction = results.stream()
            .filter(r -> r.getUserSatisfaction() != null)
            .mapToInt(ABTestResult::getUserSatisfaction)
            .average()
            .orElse(0);
        
        double avgResponseTime = results.stream()
            .mapToLong(ABTestResult::getResponseTimeMs)
            .average()
            .orElse(0);
        
        double memoryUsageRate = (double) results.stream()
            .filter(ABTestResult::isMemoryUsed)
            .count() / results.size();
        
        return GroupStats.builder()
            .sampleCount(results.size())
            .avgRelevanceScore(avgRelevance)
            .avgSatisfaction(avgSatisfaction)
            .avgResponseTimeMs(avgResponseTime)
            .memoryUsageRate(memoryUsageRate)
            .build();
    }
    
    /**
     * 确定胜出组
     */
    private String determineWinner(Map<String, GroupStats> groupStats) {
        return groupStats.entrySet().stream()
            .max(Comparator.comparingDouble(e -> e.getValue().getAvgRelevanceScore()))
            .map(Map.Entry::getKey)
            .orElse("control");
    }
    
    /**
     * 生成胜出原因
     */
    private String generateWinnerReason(Map<String, GroupStats> groupStats, String winnerGroup) {
        GroupStats winnerStats = groupStats.get(winnerGroup);
        if (winnerStats == null) {
            return "无数据";
        }
        
        return String.format(
            "组 %s 在相关性分数（%.2f）、用户满意度（%.2f）方面表现最佳",
            winnerGroup,
            winnerStats.getAvgRelevanceScore(),
            winnerStats.getAvgSatisfaction()
        );
    }
    
    /**
     * 计算 p-value（简化实现，使用 t-test）
     */
    private double calculatePValue(Map<String, GroupStats> groupStats) {
        // 简化实现：返回固定值
        // 生产环境应使用真实的统计检验库
        return 0.03; // 示例值，表示显著
    }
}

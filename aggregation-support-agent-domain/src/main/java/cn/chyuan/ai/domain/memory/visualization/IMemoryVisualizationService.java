package cn.chyuan.ai.domain.memory.visualization;

import java.time.Instant;
import java.util.List;

/**
 * 记忆可视化服务接口
 * 
 * 提供记忆图谱、时间线、统计等可视化数据
 */
public interface IMemoryVisualizationService {
    
    /**
     * 获取记忆图谱可视化数据
     *
     * @param userId  用户 ID
     * @param agentId Agent ID
     * @param maxHops 最大跳数
     * @return 图谱可视化数据
     */
    MemoryGraphVisualization getMemoryGraph(String userId, String agentId, int maxHops);
    
    /**
     * 获取记忆时间线
     *
     * @param userId    用户 ID
     * @param agentId   Agent ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 时间线数据
     */
    MemoryTimeline getMemoryTimeline(String userId, String agentId, Instant startTime, Instant endTime);
    
    /**
     * 获取记忆统计信息
     *
     * @param userId  用户 ID
     * @param agentId Agent ID
     * @return 统计信息
     */
    MemoryStatistics getMemoryStatistics(String userId, String agentId);
    
    /**
     * 获取记忆热度图数据
     *
     * @param userId  用户 ID
     * @param agentId Agent ID
     * @param days    最近多少天
     * @return 热度图数据（日期 -> 记忆数）
     */
    List<MemoryStatistics.DailyCount> getMemoryHeatmap(String userId, String agentId, int days);
    
    /**
     * 获取记忆类型分布
     *
     * @param userId  用户 ID
     * @param agentId Agent ID
     * @return 类型分布
     */
    java.util.Map<String, Long> getMemoryTypeDistribution(String userId, String agentId);
    
    /**
     * 导出记忆数据（JSON 格式）
     *
     * @param userId  用户 ID
     * @param agentId Agent ID
     * @return JSON 字符串
     */
    String exportMemoryData(String userId, String agentId);
}

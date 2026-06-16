package cn.chyuan.ai.domain.memory.compression;

import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 智能记忆压缩服务接口
 * 
 * 提供多种压缩策略：摘要压缩、聚类压缩、抽象压缩
 */
public interface IMemoryCompressionService {
    
    /**
     * 压缩记忆（自动选择策略）
     *
     * @param memories 待压缩的记忆列表
     * @param userId   用户 ID
     * @param agentId  Agent ID
     * @return 压缩结果
     */
    CompressionResult compressMemories(List<AgentMemoryEntity> memories, String userId, String agentId);
    
    /**
     * 摘要压缩（使用 LLM 生成摘要）
     *
     * @param memories 待压缩的记忆列表
     * @param userId   用户 ID
     * @param agentId  Agent ID
     * @return 压缩结果
     */
    CompressionResult compressBySummary(List<AgentMemoryEntity> memories, String userId, String agentId);
    
    /**
     * 聚类压缩（按主题聚类后压缩）
     *
     * @param memories 待压缩的记忆列表
     * @param userId   用户 ID
     * @param agentId  Agent ID
     * @return 压缩结果
     */
    CompressionResult compressByClustering(List<AgentMemoryEntity> memories, String userId, String agentId);
    
    /**
     * 抽象压缩（提取关键事实和规则）
     *
     * @param memories 待压缩的记忆列表
     * @param userId   用户 ID
     * @param agentId  Agent ID
     * @return 压缩结果
     */
    CompressionResult compressByAbstraction(List<AgentMemoryEntity> memories, String userId, String agentId);
    
    /**
     * 按时间窗口压缩（压缩旧记忆）
     *
     * @param userId       用户 ID
     * @param agentId      Agent ID
     * @param daysAgo      多少天前的记忆
     * @param targetRatio  目标压缩比
     * @return 压缩结果
     */
    CompressionResult compressOldMemories(String userId, String agentId, int daysAgo, double targetRatio);
    
    /**
     * 获取记忆压缩建议
     *
     * @param userId  用户 ID
     * @param agentId Agent ID
     * @return 压缩建议（是否应该压缩、推荐策略等）
     */
    CompressionSuggestion getCompressionSuggestion(String userId, String agentId);
    
    /**
     * 压缩建议
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class CompressionSuggestion {
        /**
         * 是否需要压缩
         */
        private boolean shouldCompress;
        
        /**
         * 推荐策略
         */
        private String recommendedStrategy;
        
        /**
         * 预计压缩比
         */
        private double estimatedRatio;
        
        /**
         * 原因说明
         */
        private String reason;
    }
}

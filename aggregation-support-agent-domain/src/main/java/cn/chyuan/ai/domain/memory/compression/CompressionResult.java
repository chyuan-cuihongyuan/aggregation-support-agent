package cn.chyuan.ai.domain.memory.compression;

import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 记忆压缩结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompressionResult {
    
    /**
     * 压缩后的记忆 ID
     */
    private String compressedMemoryId;
    
    /**
     * 压缩前的记忆数量
     */
    private int originalCount;
    
    /**
     * 压缩后的记忆数量
     */
    private int compressedCount;
    
    /**
     * 压缩比（originalCount / compressedCount）
     */
    private double compressionRatio;
    
    /**
     * 压缩后的记忆内容
     */
    private String compressedContent;
    
    /**
     * 压缩策略（SUMMARY / CLUSTER / ABSTRACT）
     */
    private String compressionStrategy;
    
    /**
     * 压缩耗时（毫秒）
     */
    private long durationMs;
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 错误信息
     */
    private String errorMessage;
}

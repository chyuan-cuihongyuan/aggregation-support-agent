package cn.chyuan.ai.domain.memory.model.valobj;

import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆匹配结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryMatch {
    
    /**
     * 记忆实体
     */
    private AgentMemoryEntity entry;
    
    /**
     * 综合评分
     */
    private Double score;
    
    /**
     * 语义相似度分数
     */
    private Double semanticScore;
    
    /**
     * 时间衰减分数
     */
    private Double recencyScore;
    
    /**
     * 重要性分数
     */
    private Double importanceScore;
}

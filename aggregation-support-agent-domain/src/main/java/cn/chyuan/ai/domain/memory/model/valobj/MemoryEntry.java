package cn.chyuan.ai.domain.memory.model.valobj;

import cn.chyuan.ai.domain.memory.model.entity.AgentMemoryEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆条目（含相似度分数）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {
    
    /**
     * 记忆实体
     */
    private AgentMemoryEntity entry;
    
    /**
     * 相似度分数
     */
    private Double score;
}

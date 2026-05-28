package cn.chyuan.ai.domain.memory.model.valobj;

import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提取的事实
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedFact {
    
    /**
     * 事实内容
     */
    private String content;
    
    /**
     * 记忆类型
     */
    private MemoryType type;
}

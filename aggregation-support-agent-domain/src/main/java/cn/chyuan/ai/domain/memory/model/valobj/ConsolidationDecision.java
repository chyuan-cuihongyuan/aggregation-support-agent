package cn.chyuan.ai.domain.memory.model.valobj;

import cn.chyuan.ai.domain.memory.model.enums.ConsolidationAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆整合决策
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsolidationDecision {
    
    /**
     * 整合动作
     */
    private ConsolidationAction action;
    
    /**
     * 合并后的内容（仅当 action 为 UPDATE 或 MERGE 时）
     */
    private String mergedContent;
    
    /**
     * 决策原因
     */
    private String reason;
}

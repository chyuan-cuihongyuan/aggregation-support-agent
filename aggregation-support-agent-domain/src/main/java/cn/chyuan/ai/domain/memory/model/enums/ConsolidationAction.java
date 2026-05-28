package cn.chyuan.ai.domain.memory.model.enums;

/**
 * 记忆整合动作枚举
 */
public enum ConsolidationAction {
    
    /**
     * 保留：保留现有记忆，不存储新记忆
     */
    KEEP,
    
    /**
     * 更新：用新内容更新现有记忆
     */
    UPDATE,
    
    /**
     * 删除：删除现有记忆（已过时或错误）
     */
    DELETE,
    
    /**
     * 合并：将两条记忆合并为一条
     */
    MERGE
}

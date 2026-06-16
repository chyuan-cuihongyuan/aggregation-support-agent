package cn.chyuan.ai.domain.memory.consolidation;

/**
 * 记忆整合类型枚举
 */
public enum ConsolidationType {
    
    /** 去重：删除内容相同的重复记忆 */
    DEDUPLICATE,
    
    /** 合并：将语义相近的记忆合并为一条 */
    MERGE,
    
    /** 抽象提炼：将多条情节记忆提炼为语义记忆 */
    ABSTRACT,
    
    /** 过期清理：清理已过期的记忆 */
    EXPIRE,
    
    /** 冲突消解：处理互相矛盾的记忆 */
    CONFLICT_RESOLUTION
}

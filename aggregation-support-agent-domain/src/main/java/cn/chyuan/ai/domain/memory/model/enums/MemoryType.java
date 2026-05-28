package cn.chyuan.ai.domain.memory.model.enums;

/**
 * 记忆类型枚举
 */
public enum MemoryType {
    
    /**
     * 事实：客观信息
     */
    FACT,
    
    /**
     * 偏好：用户偏好设置
     */
    PREFERENCE,
    
    /**
     * 决策：重要决策或结论
     */
    DECISION,
    
    /**
     * 事件片段：对话或事件的摘要
     */
    EPISODE,
    
    /**
     * 知识：专业知识或领域信息
     */
    KNOWLEDGE
}

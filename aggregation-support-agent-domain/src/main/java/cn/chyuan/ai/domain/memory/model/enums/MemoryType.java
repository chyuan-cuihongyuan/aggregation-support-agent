package cn.chyuan.ai.domain.memory.model.enums;

/**
 * 记忆类型枚举
 * 
 * 对应四层记忆模型中的长期记忆分类：
 * - 情节记忆 (EPISODE): 具体事件经历
 * - 语义记忆 (SEMANTIC): 从多次经历中提炼的抽象知识规律
 * - 程序记忆 (PROCEDURAL): 操作流程 SOP
 */
public enum MemoryType {
    
    // ===== 基础类型 =====
    
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
     * 知识：专业知识或领域信息
     */
    KNOWLEDGE,
    
    // ===== 长期记忆三子类型 =====
    
    /**
     * 情节记忆：具体事件经历（包含时间、场景、过程和结果）
     */
    EPISODE,
    
    /**
     * 语义记忆：从多次经历中提炼出来的通用知识和规律
     */
    SEMANTIC,
    
    /**
     * 程序记忆：怎么做某件事的操作流程 SOP
     */
    PROCEDURAL,
    
    // ===== 实体记忆 =====
    
    /**
     * 实体记忆：从对话中提炼的结构化事实
     */
    ENTITY
}

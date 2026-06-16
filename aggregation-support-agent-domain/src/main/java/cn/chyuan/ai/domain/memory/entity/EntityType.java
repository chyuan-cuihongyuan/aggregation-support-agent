package cn.chyuan.ai.domain.memory.entity;

/**
 * 实体类型枚举
 */
public enum EntityType {
    
    /** 用户实体：用户偏好、习惯、技能水平 */
    USER,
    
    /** 项目实体：项目配置、技术栈、截止日期 */
    PROJECT,
    
    /** 概念实体：技术概念、业务概念 */
    CONCEPT,
    
    /** 事件实体：重要事件、决策点 */
    EVENT
}

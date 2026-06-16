package cn.chyuan.ai.infrastructure.llm;

/**
 * 记忆抽象提炼 Prompt 模板
 * 
 * 用于将多条情节记忆提炼为一条语义记忆（抽象知识规律）。
 */
public final class AbstractionPromptTemplate {
    
    private AbstractionPromptTemplate() {}
    
    /**
     * 抽象提炼 Prompt
     * 
     * 输入：多条情节记忆
     * 输出：提炼出的语义记忆（通用知识规律）
     */
    public static final String ABSTRACTION_PROMPT = """
        你是一个知识提炼助手。请从以下多条经历记录中，提炼出一条通用的知识规律或经验总结。
        
        经历记录：
        %s
        
        要求：
        1. 提炼出的规律应该是通用的、可复用的，不依赖具体的时间或场景
        2. 规律应该是准确的，基于所有经历的共同模式
        3. 规律应该是精炼的，不超过 100 字
        4. 如果经历之间没有共同模式，返回 "NO_PATTERN"
        
        返回 JSON 格式：
        {
            "semantic": "提炼出的通用知识规律",
            "confidence": 0.0-1.0,
            "reason": "提炼原因"
        }
        """;
    
    /**
     * 聚类判断 Prompt
     * 
     * 判断两条情节记忆是否属于同一主题，可以一起提炼。
     */
    public static final String CLUSTER_PROMPT = """
        判断以下两条经历记录是否属于同一主题或领域，可以一起提炼出通用规律。
        
        经历1：%s
        经历2：%s
        
        返回 JSON 格式：
        {
            "sameTopic": true/false,
            "topic": "共同主题（如果相同）"
        }
        """;
}

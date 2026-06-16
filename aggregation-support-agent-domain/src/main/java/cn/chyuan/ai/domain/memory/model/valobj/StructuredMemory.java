package cn.chyuan.ai.domain.memory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化记忆值对象
 * 用于存储从对话中提取的结构化信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredMemory {
    
    /** 用户偏好 */
    @Builder.Default
    private List<String> userPreferences = new ArrayList<>();
    
    /** 决策 */
    @Builder.Default
    private List<String> decisions = new ArrayList<>();
    
    /** 行动项 */
    @Builder.Default
    private List<String> actionItems = new ArrayList<>();
    
    /** 事实 */
    @Builder.Default
    private List<String> facts = new ArrayList<>();
    
    /** 实体 */
    @Builder.Default
    private List<String> entities = new ArrayList<>();
    
    /** 关键主题 */
    @Builder.Default
    private List<String> keyTopics = new ArrayList<>();
    
    /** 情感倾向 */
    private String sentiment;
    
    /** 重要性评分 */
    private Integer importance;
    
    /**
     * 创建空的结构化记忆
     */
    public static StructuredMemory empty() {
        return StructuredMemory.builder().build();
    }
    
    /**
     * 是否为空
     */
    public boolean isEmpty() {
        return userPreferences.isEmpty() 
            && decisions.isEmpty() 
            && actionItems.isEmpty() 
            && facts.isEmpty() 
            && entities.isEmpty() 
            && keyTopics.isEmpty()
            && sentiment == null
            && importance == null;
    }
}

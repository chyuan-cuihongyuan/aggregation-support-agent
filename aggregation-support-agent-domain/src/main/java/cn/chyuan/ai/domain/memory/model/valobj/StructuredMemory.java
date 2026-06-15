package cn.chyuan.ai.domain.memory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化记忆 - 从对话中提取的结构化信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredMemory {
    
    /** 用户偏好 */
    @Builder.Default
    private List<String> userPreferences = new ArrayList<>();
    
    /** 决策记录 */
    @Builder.Default
    private List<String> decisions = new ArrayList<>();
    
    /** 待办事项 */
    @Builder.Default
    private List<String> actionItems = new ArrayList<>();
    
    /** 事实陈述 */
    @Builder.Default
    private List<String> facts = new ArrayList<>();
    
    /** 实体（人名、地名、组织等） */
    @Builder.Default
    private List<String> entities = new ArrayList<>();
    
    /** 关键主题 */
    @Builder.Default
    private List<String> keyTopics = new ArrayList<>();
    
    /** 情感倾向 */
    private String sentiment;
    
    /** 重要性评分（1-10） */
    @Builder.Default
    private Integer importance = 5;
    
    /**
     * 创建空的结构化记忆
     */
    public static StructuredMemory empty() {
        return StructuredMemory.builder()
                .userPreferences(new ArrayList<>())
                .decisions(new ArrayList<>())
                .actionItems(new ArrayList<>())
                .facts(new ArrayList<>())
                .entities(new ArrayList<>())
                .keyTopics(new ArrayList<>())
                .sentiment("neutral")
                .importance(5)
                .build();
    }
    
    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return userPreferences.isEmpty() 
                && decisions.isEmpty() 
                && actionItems.isEmpty() 
                && facts.isEmpty() 
                && entities.isEmpty() 
                && keyTopics.isEmpty();
    }
    
    /**
     * 获取所有信息的摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        
        if (!userPreferences.isEmpty()) {
            sb.append("用户偏好: ").append(String.join(", ", userPreferences)).append("\n");
        }
        if (!decisions.isEmpty()) {
            sb.append("决策: ").append(String.join(", ", decisions)).append("\n");
        }
        if (!actionItems.isEmpty()) {
            sb.append("待办: ").append(String.join(", ", actionItems)).append("\n");
        }
        if (!facts.isEmpty()) {
            sb.append("事实: ").append(String.join(", ", facts)).append("\n");
        }
        if (!entities.isEmpty()) {
            sb.append("实体: ").append(String.join(", ", entities)).append("\n");
        }
        if (!keyTopics.isEmpty()) {
            sb.append("主题: ").append(String.join(", ", keyTopics)).append("\n");
        }
        if (sentiment != null) {
            sb.append("情感: ").append(sentiment).append("\n");
        }
        sb.append("重要性: ").append(importance).append("/10\n");
        
        return sb.toString();
    }
}

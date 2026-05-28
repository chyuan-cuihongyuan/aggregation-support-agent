package cn.chyuan.ai.domain.memory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆检索选项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecallOptions {
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 智能体ID
     */
    private String agentId;
    
    /**
     * 记忆作用域
     */
    private String scope;
    
    /**
     * 返回结果数量限制
     */
    private Integer limit;
    
    /**
     * 最小相似度分数
     */
    private Double minScore;
    
    /**
     * 语义相似度权重
     */
    private Double semanticWeight;
    
    /**
     * 时间衰减权重
     */
    private Double recencyWeight;
    
    /**
     * 重要性权重
     */
    private Double importanceWeight;
    
    /**
     * 时间衰减半衰期（天）
     */
    private Integer recencyHalfLifeDays;
}

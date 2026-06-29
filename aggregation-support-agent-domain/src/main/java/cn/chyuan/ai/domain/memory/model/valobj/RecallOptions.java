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
     * 最小相似度分数（复合分数门槛：语义/时效/重要性加权后的总分校准）
     */
    private Double minScore;

    /**
     * 最小语义相似度分数（语义硬门槛）
     * <p>
     * 独立于复合分数的一道硬过滤：即使记忆时效很近、重要性很高，只要语义相似度低于此值就被直接丢弃，
     * 防止仅词面重叠（如"内存使用率高"vs"内存条进货价"）的记忆被时效/重要性抬高后误召回。
     * 设为 null 时取服务层默认值。
     */
    private Double minSemanticScore;

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

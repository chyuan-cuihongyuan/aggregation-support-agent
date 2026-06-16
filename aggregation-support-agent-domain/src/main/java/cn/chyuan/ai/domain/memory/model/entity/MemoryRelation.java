package cn.chyuan.ai.domain.memory.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 记忆关联实体
 * 用于存储记忆之间的关联关系
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRelation {
    
    /** 关联ID */
    private String relationId;
    
    /** 源记忆ID */
    private String sourceMemoryId;
    
    /** 目标记忆ID */
    private String targetMemoryId;
    
    /** 关联类型 */
    private RelationType relationType;
    
    /** 关联强度（0-1） */
    private Double strength;
    
    /** 关联描述 */
    private String description;
    
    /** 租户ID */
    private String tenantId;
    
    /** 用户ID */
    private String userId;
    
    /** 创建时间 */
    private LocalDateTime createdAt;
    
    /** 关联类型枚举 */
    public enum RelationType {
        /** 因果关系 */
        CAUSAL,
        /** 时间顺序 */
        TEMPORAL,
        /** 主题关联 */
        THEMATIC,
        /** 引用关系 */
        REFERENCE,
        /** 相似关系 */
        SIMILAR,
        /** 上下文关系 */
        CONTEXTUAL
    }
}

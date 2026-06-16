package cn.chyuan.ai.domain.memory.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 实体图谱值对象（用于知识图谱查询结果）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityGraph {
    
    /** 中心实体 */
    private AgentEntityMemoryEntity centerEntity;
    
    /** 关联实体列表 */
    private List<RelatedEntity> relatedEntities;
    
    /**
     * 关联实体
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedEntity {
        
        /** 关系类型 */
        private String relationType;
        
        /** 关系方向 (OUTGOING/INCOMING) */
        private String direction;
        
        /** 关联的实体 */
        private AgentEntityMemoryEntity entity;
    }
}

package cn.chyuan.ai.domain.memory.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 从对话中提取的实体值对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedEntity {
    
    /** 实体名称 */
    private String name;
    
    /** 实体类型 */
    private EntityType type;
    
    /** 实体属性 */
    private Map<String, Object> attributes;
    
    /** 提取置信度 0-1 */
    private Double confidence;
    
    /** 关联关系 (关系类型 -> 目标实体名称) */
    private Map<String, String> relations;
}

package cn.chyuan.ai.domain.memory.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 实体记忆实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEntityMemoryEntity {
    
    private Long id;
    private String entityId;
    private String tenantId;
    private String userId;
    private EntityType entityType;
    private String entityName;
    private Map<String, Object> attributes;
    private List<String> sourceMemoryIds;
    private Double confidence;
    private Integer status;
    private Instant createdAt;
    private Instant updatedAt;
}

package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * 图谱实体 DTO
 */
@Data
public class GraphEntityDTO {
    private String entityId;
    private String entityName;
    private String entityType;
    private String description;
    private Map<String, Object> properties;
    private String sourceDocumentId;
}

package cn.chyuan.ai.domain.knowledgegraph.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

/**
 * 知识图谱实体
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphEntity {

    /** 实体ID */
    private String entityId;

    /** 实体名称 */
    private String entityName;

    /** 实体类型：CONCEPT | PERSON | ORGANIZATION | TECHNOLOGY | PRODUCT | EVENT */
    private String entityType;

    /** 实体描述 */
    private String description;

    /** 扩展属性 */
    private Map<String, Object> properties;

    /** 来源文档ID */
    private String sourceDocumentId;

    /** 来源分块ID */
    private String sourceChunkId;

    /** 实体嵌入向量（1024维） */
    private float[] embedding;

    /** 创建时间 */
    private Date createdAt;

    /** 更新时间 */
    private Date updatedAt;
}

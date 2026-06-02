package cn.chyuan.ai.domain.knowledgegraph.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

/**
 * 知识图谱关系
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphRelation {

    /** 关系ID */
    private String relationId;

    /** 源实体ID */
    private String sourceEntityId;

    /** 目标实体ID */
    private String targetEntityId;

    /** 源实体名称（LLM抽取结果，用于构建阶段映射实体ID） */
    private String sourceEntityName;

    /** 目标实体名称（LLM抽取结果，用于构建阶段映射实体ID） */
    private String targetEntityName;

    /** 关系类型：RELATED_TO | PART_OF | DEPENDS_ON | BELONGS_TO | USES | LOCATED_IN */
    private String relationType;

    /** 关系描述 */
    private String description;

    /** 扩展属性 */
    private Map<String, Object> properties;

    /** LLM 抽取置信度 */
    private Float confidence;

    /** 来源文档ID */
    private String sourceDocumentId;

    /** 创建时间 */
    private Date createdAt;
}

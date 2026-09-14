package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图节点值对象（AM1：实体节点，name 归一后为节点键）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphNodeVO {

    /** 节点键（归一实体名） */
    private String nodeKey;

    /** 原始实体名 */
    private String entityName;

    /** 实体类型 */
    private String entityType;

    /** 关联抽取实体ID（可空） */
    private String entityId;
}

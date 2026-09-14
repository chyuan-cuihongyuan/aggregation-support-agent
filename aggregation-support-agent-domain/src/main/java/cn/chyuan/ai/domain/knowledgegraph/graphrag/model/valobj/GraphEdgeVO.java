package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图边值对象（AM1：关系边，source|type|target 组合唯一）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphEdgeVO {

    /** 边键（source|type|target） */
    private String edgeKey;

    /** 源节点键 */
    private String sourceKey;

    /** 目标节点键 */
    private String targetKey;

    /** 关系类型 */
    private String relationType;
}

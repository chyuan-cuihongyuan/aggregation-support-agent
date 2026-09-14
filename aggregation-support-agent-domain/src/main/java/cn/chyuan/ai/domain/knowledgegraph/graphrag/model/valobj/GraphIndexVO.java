package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 图索引值对象（AM1：text_units + 节点/边 + 来源块映射，构建可重放）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphIndexVO {

    /** 来源文档ID */
    private String documentId;

    /** 文本块列表（ordinal 有序） */
    private List<TextUnitVO> textUnits;

    /** 实体节点列表（按 nodeKey 排序） */
    private List<GraphNodeVO> nodes;

    /** 关系边列表（按 edgeKey 排序） */
    private List<GraphEdgeVO> edges;

    /** 实体→来源块ID列表（unitId 有序） */
    private Map<String, List<String>> entitySources;

    /** 边键→来源块ID列表（unitId 有序） */
    private Map<String, List<String>> relationSources;

    /** 索引哈希（规范化序列化 SHA-256，重放校验用） */
    private String indexHash;
}

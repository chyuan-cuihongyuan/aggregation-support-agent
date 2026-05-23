package cn.chyuan.ai.domain.knowledgegraph.model.valobj;

import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphRelation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 图谱检索结果值对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphSearchResultVO {

    /** 匹配的实体列表 */
    private List<GraphEntity> matchedEntities;

    /** 匹配的关系列表 */
    private List<GraphRelation> matchedRelations;

    /** 子图遍历的邻居节点 */
    private List<GraphEntity> neighborEntities;

    /** 子图的自然语言描述 */
    private String subgraphDescription;

    /** 匹配分数 */
    private Float score;

    /** 元数据 */
    private Map<String, Object> metadata;
}

package cn.chyuan.ai.domain.knowledgegraph.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 子图数据（可视化用）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SubgraphVO {

    /** 节点列表 */
    private List<GraphNodeVO> nodes;

    /** 边列表 */
    private List<GraphEdgeVO> edges;

    /**
     * 图谱节点
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class GraphNodeVO {
        private String id;
        private String label;
        private String type;
        private Map<String, Object> properties;
        private Float score;
    }

    /**
     * 图谱边
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class GraphEdgeVO {
        private String id;
        private String source;
        private String target;
        private String label;
        private String type;
        private Map<String, Object> properties;
    }
}

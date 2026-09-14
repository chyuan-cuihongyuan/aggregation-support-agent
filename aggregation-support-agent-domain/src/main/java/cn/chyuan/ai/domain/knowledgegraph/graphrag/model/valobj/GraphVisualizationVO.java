package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 图谱可视化数据值对象（AM9：nodes/edges + 社区着色与度数元数据，前端零依赖渲染数据面）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphVisualizationVO {

    /** 节点列表 */
    private List<VisNodeVO> nodes;

    /** 边列表 */
    private List<VisEdgeVO> edges;

    /** 导出前图内节点总数 */
    private int totalNodes;

    /** 导出前图内边总数 */
    private int totalEdges;

    /** 是否触发数量上限采样 */
    private boolean sampled;

    /** 节点（id=节点键，community=着色分组，degree=度数） */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class VisNodeVO {
        private String id;
        private String label;
        private String community;
        private int degree;
    }

    /** 边（source/target=节点键） */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class VisEdgeVO {
        private String source;
        private String target;
        private String type;
    }
}

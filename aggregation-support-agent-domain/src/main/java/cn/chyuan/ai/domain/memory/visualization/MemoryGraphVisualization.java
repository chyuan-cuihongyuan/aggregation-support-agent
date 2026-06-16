package cn.chyuan.ai.domain.memory.visualization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 记忆图谱可视化数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryGraphVisualization {
    
    /**
     * 节点列表
     */
    private List<GraphNode> nodes;
    
    /**
     * 边列表（关系）
     */
    private List<GraphEdge> edges;
    
    /**
     * 统计信息
     */
    private GraphStats stats;
    
    /**
     * 图谱节点
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphNode {
        /**
         * 节点 ID
         */
        private String id;
        
        /**
         * 节点标签
         */
        private String label;
        
        /**
         * 节点类型（USER / AGENT / CONCEPT / TOOL / MEMORY）
         */
        private String type;
        
        /**
         * 节点属性
         */
        private Map<String, Object> properties;
        
        /**
         * 节点大小（根据重要性）
         */
        private Double size;
        
        /**
         * 节点颜色（根据类型）
         */
        private String color;
    }
    
    /**
     * 图谱边
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphEdge {
        /**
         * 边 ID
         */
        private String id;
        
        /**
         * 源节点 ID
         */
        private String source;
        
        /**
         * 目标节点 ID
         */
        private String target;
        
        /**
         * 关系类型
         */
        private String relationType;
        
        /**
         * 关系权重
         */
        private Double weight;
        
        /**
         * 边属性
         */
        private Map<String, Object> properties;
    }
    
    /**
     * 图谱统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphStats {
        /**
         * 节点总数
         */
        private Integer nodeCount;
        
        /**
         * 边总数
         */
        private Integer edgeCount;
        
        /**
         * 节点类型分布
         */
        private Map<String, Integer> nodeTypeDistribution;
        
        /**
         * 关系类型分布
         */
        private Map<String, Integer> relationTypeDistribution;
        
        /**
         * 平均度数
         */
        private Double avgDegree;
        
        /**
         * 最大连通分量大小
         */
        private Integer largestComponentSize;
    }
}

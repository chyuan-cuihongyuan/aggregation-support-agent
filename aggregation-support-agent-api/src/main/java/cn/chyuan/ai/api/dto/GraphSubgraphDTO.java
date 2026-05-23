package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 图谱子图 DTO（可视化用）
 */
@Data
public class GraphSubgraphDTO {
    private List<GraphNodeDTO> nodes;
    private List<GraphEdgeDTO> edges;

    @Data
    public static class GraphNodeDTO {
        private String id;
        private String label;
        private String type;
        private Map<String, Object> properties;
        private Double score;
    }

    @Data
    public static class GraphEdgeDTO {
        private String id;
        private String source;
        private String target;
        private String label;
        private String type;
        private Map<String, Object> properties;
    }
}

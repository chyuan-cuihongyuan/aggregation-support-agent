package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphVisualizationVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱可视化数据导出器（工单 0314 AM9）。
 * 图索引+社区划分 → 带社区着色与度数元数据的图 JSON；
 * 节点超上限按「度数降序 + 键序」采样保留，边取保留节点的导出子集。
 * React Flow 数据模型思想的零依赖数据面，domain 纯函数。
 */
public class GraphVisualizationExporter {

    public GraphVisualizationVO export(GraphIndexVO index, CommunityPartitionVO partition, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("节点上限必须为正数");
        }
        // 度数统计
        Map<String, Integer> degrees = new HashMap<>();
        for (GraphEdgeVO edge : index.getEdges()) {
            degrees.merge(edge.getSourceKey(), 1, Integer::sum);
            degrees.merge(edge.getTargetKey(), 1, Integer::sum);
        }
        // 采样：度数降序 + 键序稳定取前 limit
        List<String> nodeKeys = new ArrayList<>(index.getNodes().stream()
                .map(cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphNodeVO::getNodeKey)
                .toList());
        boolean sampled = nodeKeys.size() > limit;
        if (sampled) {
            nodeKeys.sort(Comparator
                    .comparingInt((String key) -> degrees.getOrDefault(key, 0)).reversed()
                    .thenComparing(Comparator.naturalOrder()));
            nodeKeys = nodeKeys.subList(0, limit);
        }
        Map<String, Boolean> kept = new LinkedHashMap<>();
        nodeKeys.forEach(key -> kept.put(key, true));

        List<GraphVisualizationVO.VisNodeVO> nodes = new ArrayList<>();
        for (var node : index.getNodes()) {
            if (!kept.containsKey(node.getNodeKey())) {
                continue;
            }
            nodes.add(GraphVisualizationVO.VisNodeVO.builder()
                    .id(node.getNodeKey())
                    .label(node.getEntityName())
                    .community(partition == null ? null : partition.getNodeToCommunity().get(node.getNodeKey()))
                    .degree(degrees.getOrDefault(node.getNodeKey(), 0))
                    .build());
        }
        List<GraphVisualizationVO.VisEdgeVO> edges = new ArrayList<>();
        for (GraphEdgeVO edge : index.getEdges()) {
            if (kept.containsKey(edge.getSourceKey()) && kept.containsKey(edge.getTargetKey())) {
                edges.add(GraphVisualizationVO.VisEdgeVO.builder()
                        .source(edge.getSourceKey())
                        .target(edge.getTargetKey())
                        .type(edge.getRelationType())
                        .build());
            }
        }
        return GraphVisualizationVO.builder()
                .nodes(nodes)
                .edges(edges)
                .totalNodes(index.getNodes().size())
                .totalEdges(index.getEdges().size())
                .sampled(sampled)
                .build();
    }
}

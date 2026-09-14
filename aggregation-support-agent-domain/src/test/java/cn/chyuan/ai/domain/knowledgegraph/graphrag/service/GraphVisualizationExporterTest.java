package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphNodeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphVisualizationVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图谱可视化导出器单测（工单 0314 AM9）：结构完整/采样/边界。
 */
class GraphVisualizationExporterTest {

    private final GraphVisualizationExporter exporter = new GraphVisualizationExporter();

    @Test
    void 图JSON结构字段完整带社区着色() {
        GraphIndexVO index = sampleIndex();
        CommunityPartitionVO partition = partitionOf(Map.of(
                "a", "c_b", "b", "c_b", "c", "c_c"));
        GraphVisualizationVO vis = exporter.export(index, partition, 100);
        assertEquals(3, vis.getNodes().size());
        assertEquals(2, vis.getEdges().size());
        assertTrue(!vis.isSampled());
        assertEquals(3, vis.getTotalNodes());
        // 节点元数据：社区着色 + 度数
        GraphVisualizationVO.VisNodeVO a = vis.getNodes().stream()
                .filter(node -> node.getId().equals("a")).findFirst().orElseThrow();
        assertEquals("c_b", a.getCommunity());
        assertEquals(2, a.getDegree(), "a 与 b、c 相连度数为 2");
        assertEquals("A", a.getLabel(), "label 为原始实体名");
        // 边字段
        GraphVisualizationVO.VisEdgeVO edge = vis.getEdges().get(0);
        assertEquals("RELATED_TO", edge.getType());
    }

    @Test
    void 超上限按度数优先采样() {
        GraphIndexVO index = sampleIndex();
        GraphVisualizationVO vis = exporter.export(index, null, 1);
        assertEquals(1, vis.getNodes().size());
        assertTrue(vis.isSampled());
        // 度数最高的 a 被保留
        assertEquals("a", vis.getNodes().get(0).getId());
        assertEquals(3, vis.getTotalNodes());
        // 无社区划分时 community 为 null
        assertTrue(vis.getNodes().get(0).getCommunity() == null);
        // 只保留 1 个节点 → 导出边为空
        assertTrue(vis.getEdges().isEmpty());
    }

    @Test
    void 空图与非法上限() {
        GraphVisualizationVO empty = exporter.export(GraphIndexVO.builder()
                .documentId("e").textUnits(Collections.emptyList())
                .nodes(new ArrayList<>()).edges(new ArrayList<>())
                .entitySources(Collections.emptyMap()).relationSources(Collections.emptyMap())
                .build(), null, 10);
        assertEquals(0, empty.getNodes().size());
        assertTrue(!empty.isSampled());
        assertThrows(IllegalArgumentException.class, () -> exporter.export(sampleIndex(), null, 0));
    }

    private GraphIndexVO sampleIndex() {
        return GraphIndexVO.builder()
                .documentId("doc")
                .textUnits(Collections.emptyList())
                .nodes(Arrays.asList(node("a"), node("b"), node("c")))
                .edges(Arrays.asList(edge("a", "b"), edge("a", "c")))
                .entitySources(Collections.emptyMap())
                .relationSources(Collections.emptyMap())
                .build();
    }

    private GraphNodeVO node(String key) {
        return GraphNodeVO.builder().nodeKey(key).entityName(key.toUpperCase()).entityType("CONCEPT").build();
    }

    private GraphEdgeVO edge(String source, String target) {
        return GraphEdgeVO.builder().edgeKey(source + "|RELATED_TO|" + target)
                .sourceKey(source).targetKey(target).relationType("RELATED_TO").build();
    }

    private CommunityPartitionVO partitionOf(Map<String, String> nodeToCommunity) {
        Map<String, List<String>> communities = new LinkedHashMap<>();
        nodeToCommunity.forEach((node, community) ->
                communities.computeIfAbsent(community, k -> new ArrayList<>()).add(node));
        return CommunityPartitionVO.builder()
                .nodeToCommunity(nodeToCommunity)
                .communities(communities)
                .iterations(1).converged(true).build();
    }
}

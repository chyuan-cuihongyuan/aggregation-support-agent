package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GroundednessReportVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphNodeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphQualityVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图谱质量评估器单测（工单 0313 AM8）：四项指标/groundedness/边界。
 */
class GraphQualityEvaluatorTest {

    private final GraphQualityEvaluator evaluator = new GraphQualityEvaluator();

    /** 链图 a-b-c + 孤儿 d；a、b 有来源块，c、d 无 */
    private GraphIndexVO sampleIndex() {
        return GraphIndexVO.builder()
                .documentId("doc")
                .textUnits(Collections.emptyList())
                .nodes(Arrays.asList(
                        node("a"), node("b"), node("c"), node("d")))
                .edges(Arrays.asList(edge("a", "b"), edge("b", "c")))
                .entitySources(new LinkedHashMap<>(Map.of(
                        "a", List.of("doc#u0"),
                        "b", List.of("doc#u1"))))
                .relationSources(Collections.emptyMap())
                .build();
    }

    private GraphNodeVO node(String key) {
        return GraphNodeVO.builder().nodeKey(key).entityName(key).entityType("CONCEPT").build();
    }

    private GraphEdgeVO edge(String source, String target) {
        return GraphEdgeVO.builder().edgeKey(source + "|RELATED_TO|" + target)
                .sourceKey(source).targetKey(target).relationType("RELATED_TO").build();
    }

    private CommunityPartitionVO partition(Map<String, List<String>> communities) {
        Map<String, String> nodeToCommunity = new LinkedHashMap<>();
        communities.forEach((community, members) -> members.forEach(member -> nodeToCommunity.put(member, community)));
        return CommunityPartitionVO.builder()
                .nodeToCommunity(nodeToCommunity)
                .communities(communities)
                .iterations(1).converged(true).build();
    }

    @Test
    void 四项指标计算正确() {
        GraphQualityVO quality = evaluator.evaluate(sampleIndex(),
                partition(Map.of("c_a", Arrays.asList("a", "b", "c"), "c_d", List.of("d"))));
        assertEquals(0.5, quality.getSourceCoverage(), 1e-9, "2/4 实体有来源块");
        assertEquals(2, quality.getConnectedComponents(), "链 abc + 孤儿 d = 2 分量");
        assertEquals(0.25, quality.getOrphanRate(), 1e-9, "孤儿 d 占 1/4");
        assertEquals(0.75, quality.getLargestCommunityRatio(), 1e-9, "最大社区 3/4");
        assertTrue(quality.getCommunitySizeEntropy() > 0);
        assertEquals(4, quality.getNodeCount());
    }

    @Test
    void 无社区划分时社区指标记零() {
        GraphQualityVO quality = evaluator.evaluate(sampleIndex(), null);
        assertEquals(0.0, quality.getLargestCommunityRatio());
        assertEquals(0.0, quality.getCommunitySizeEntropy());
        assertEquals(2, quality.getConnectedComponents());
    }

    @Test
    void 摘要groundedness支撑与无支撑标记() {
        List<GraphEdgeVO> internal = Arrays.asList(edge("a", "b"));
        GroundednessReportVO report = evaluator.assertGroundedness(
                "a 与 b 存在关联。完全无关的一句陈述。",
                Arrays.asList("a", "b"), internal);
        assertEquals(2, report.getSentences().size());
        assertTrue(report.getSentences().get(0).isSupported());
        assertTrue(!report.getSentences().get(1).isSupported());
        assertEquals(0.5, report.getScore(), 1e-9);
        assertEquals(1, report.getUnsupportedCount());
        // 关系两端同现也可支撑
        GroundednessReportVO viaRelation = evaluator.assertGroundedness(
                "b 关联 a 的描述。", Arrays.asList(), internal);
        assertTrue(viaRelation.getSentences().get(0).isSupported());
    }

    @Test
    void 空图与空摘要边界() {
        GraphQualityVO empty = evaluator.evaluate(GraphIndexVO.builder()
                .documentId("e").textUnits(Collections.emptyList())
                .nodes(Collections.emptyList()).edges(Collections.emptyList())
                .entitySources(Collections.emptyMap()).relationSources(Collections.emptyMap())
                .build(), null);
        assertEquals(0, empty.getNodeCount());
        assertEquals(0.0, empty.getSourceCoverage());
        GroundednessReportVO blank = evaluator.assertGroundedness("", List.of("a"), Collections.emptyList());
        assertEquals(0.0, blank.getScore());
        assertEquals(0, blank.getSentences().size());
    }
}

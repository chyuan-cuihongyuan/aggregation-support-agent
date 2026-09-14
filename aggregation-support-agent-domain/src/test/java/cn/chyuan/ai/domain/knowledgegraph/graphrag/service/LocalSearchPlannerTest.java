package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphNodeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.LocalSearchContextVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.TextUnitVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local Search 规划器单测（工单 0309 AM4）：锚定三态/跳数与度数截断/预算裁剪/边界。
 */
class LocalSearchPlannerTest {

    /** 星形+链形图：hub-a、hub-b；b-c；c-d（两跳可达 d） */
    private GraphIndexVO sampleIndex() {
        return GraphIndexVO.builder()
                .documentId("doc")
                .textUnits(Arrays.asList(
                        TextUnitVO.builder().unitId("doc#u0").documentId("doc").ordinal(0).content("spring 内容").build(),
                        TextUnitVO.builder().unitId("doc#u1").documentId("doc").ordinal(1).content("boot 内容").build(),
                        TextUnitVO.builder().unitId("doc#u2").documentId("doc").ordinal(2).content("cloud 内容").build()))
                .nodes(Arrays.asList(node("a"), node("b"), node("c"), node("d")))
                .edges(Arrays.asList(edge("a", "b"), edge("b", "c"), edge("c", "d")))
                .entitySources(new LinkedHashMap<>(Map.of(
                        "a", List.of("doc#u0"),
                        "b", List.of("doc#u1"),
                        "d", List.of("doc#u2"))))
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

    @Test
    void 锚定命中与两跳邻域扩展() {
        LocalSearchPlanner planner = new LocalSearchPlanner(2, 10, 1000);
        LocalSearchContextVO context = planner.search(sampleIndex(), "a");
        assertEquals("HIT", context.getAnchorStatus());
        assertEquals("a", context.getAnchorKey());
        // 两跳（2 条边）：b、c 进入，d 在第 3 条边外
        assertEquals(Arrays.asList("a", "b", "c"), context.getEntities());
        assertEquals(Arrays.asList("a|RELATED_TO|b", "b|RELATED_TO|c"), context.getRelations());
        assertEquals(Arrays.asList("doc#u0", "doc#u1"), context.getTextUnits());
        // 三跳：d 进入
        LocalSearchContextVO threeHops = new LocalSearchPlanner(3, 10, 1000).search(sampleIndex(), "a");
        assertEquals(Arrays.asList("a", "b", "c", "d"), threeHops.getEntities());
        assertEquals(Arrays.asList("doc#u0", "doc#u1", "doc#u2"), threeHops.getTextUnits());
        assertTrue(threeHops.getEstimatedTokens() > 0);
        assertTrue(!threeHops.isTruncated());
    }

    @Test
    void 一跳限制与度数截断() {
        // 一跳：只有 b
        LocalSearchContextVO oneHop = new LocalSearchPlanner(1, 10, 1000).search(sampleIndex(), "a");
        assertEquals(Arrays.asList("a", "b"), oneHop.getEntities());
        assertEquals(Collections.singletonList("a|RELATED_TO|b"), oneHop.getRelations());
        // 度数截断 1：b 的邻居 {a,c} 键序取前 1 → 只扩 a
        LocalSearchContextVO truncated = new LocalSearchPlanner(2, 1, 1000).search(sampleIndex(), "b");
        assertEquals(Arrays.asList("b", "a"), truncated.getEntities());
        assertEquals(Collections.singletonList("a|RELATED_TO|b"), truncated.getRelations());
    }

    @Test
    void 未命中与歧义消解() {
        LocalSearchPlanner planner = new LocalSearchPlanner(1, 5, 500);
        // 未命中
        LocalSearchContextVO miss = planner.search(sampleIndex(), "不存在");
        assertEquals("MISS", miss.getAnchorStatus());
        assertNull(miss.getAnchorKey());
        assertTrue(miss.getEntities().isEmpty());
        // 歧义：key 会同时子串命中 "k1"、"k2"
        LocalSearchContextVO ambiguous = planner.search(sampleIndex(), "k");
        // sampleIndex 无 k 前缀节点 → MISS；换歧义图
        GraphIndexVO ambiguousIndex = GraphIndexVO.builder()
                .documentId("doc2")
                .textUnits(Collections.emptyList())
                .nodes(Arrays.asList(node("k1"), node("k2")))
                .edges(Collections.emptyList())
                .entitySources(Collections.emptyMap())
                .relationSources(Collections.emptyMap())
                .build();
        LocalSearchContextVO two = planner.search(ambiguousIndex, "k");
        assertEquals("AMBIGUOUS", two.getAnchorStatus());
        assertEquals(Arrays.asList("k1", "k2"), two.getCandidateKeys());
        // 单候选自动采纳
        GraphIndexVO singleIndex = GraphIndexVO.builder()
                .documentId("doc3")
                .textUnits(Collections.emptyList())
                .nodes(Collections.singletonList(node("k1")))
                .edges(Collections.emptyList())
                .entitySources(Collections.emptyMap())
                .relationSources(Collections.emptyMap())
                .build();
        LocalSearchContextVO adopted = planner.search(singleIndex, "k");
        assertEquals("HIT", adopted.getAnchorStatus());
        assertEquals("k1", adopted.getAnchorKey());
    }

    @Test
    void 预算裁剪顺序为实体优先于原文() {
        // 预算 4：装下锚点+两跳内前两个实体，关系与原文块被裁
        LocalSearchPlanner planner = new LocalSearchPlanner(2, 10, 4);
        LocalSearchContextVO context = planner.search(sampleIndex(), "a");
        assertEquals("HIT", context.getAnchorStatus());
        assertTrue(context.isTruncated(), "小预算应触发截断");
        assertEquals(3, context.getEntities().size(), "实体优先装填（a/b/c）");
        assertTrue(context.getRelations().isEmpty(), "关系被裁剪");
        assertTrue(context.getTextUnits().isEmpty(), "原文块被裁剪");
    }

    @Test
    void 空索引与非法输入() {
        GraphIndexVO empty = GraphIndexVO.builder()
                .documentId("e").textUnits(Collections.emptyList())
                .nodes(Collections.emptyList()).edges(Collections.emptyList())
                .entitySources(Collections.emptyMap()).relationSources(Collections.emptyMap())
                .build();
        assertEquals("MISS", new LocalSearchPlanner(1, 1, 100).search(empty, "x").getAnchorStatus());
        assertThrows(IllegalArgumentException.class, () -> new LocalSearchPlanner(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LocalSearchPlanner(1, 1, 1).search(sampleIndex(), " "));
    }
}

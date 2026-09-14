package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.WeightedEdgeVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签传播社区发现单测（工单 0307 AM2）：确定性/收敛与上限/孤立节点/边界。
 */
class LabelPropagationCommunityDetectorTest {

    private final LabelPropagationCommunityDetector detector = new LabelPropagationCommunityDetector();

    /** 两个加权三角簇（簇内 2.0）+ 一条弱桥边（1.0）：应划成两个社区 */
    private List<WeightedEdgeVO> twoClusters() {
        return Arrays.asList(
                edge("a", "b", 2.0), edge("b", "c", 2.0), edge("c", "a", 2.0),
                edge("x", "y", 2.0), edge("y", "z", 2.0), edge("z", "x", 2.0),
                edge("c", "x", 1.0));
    }

    private WeightedEdgeVO edge(String a, String b) {
        return edge(a, b, 1.0);
    }

    private WeightedEdgeVO edge(String a, String b, double weight) {
        return WeightedEdgeVO.builder().sourceKey(a).targetKey(b).weight(weight).build();
    }

    @Test
    void 双簇图划分确定且重放一致() {
        List<String> nodes = Arrays.asList("a", "b", "c", "x", "y", "z");
        CommunityPartitionVO first = detector.detect(nodes, twoClusters(), 20);
        CommunityPartitionVO second = detector.detect(nodes, twoClusters(), 20);
        assertEquals(first.getNodeToCommunity(), second.getNodeToCommunity());
        assertTrue(first.isConverged());
        // a-b-c 一簇、x-y-z 一簇
        assertEquals(first.getNodeToCommunity().get("a"), first.getNodeToCommunity().get("b"));
        assertEquals(first.getNodeToCommunity().get("b"), first.getNodeToCommunity().get("c"));
        assertEquals(first.getNodeToCommunity().get("x"), first.getNodeToCommunity().get("y"));
        assertEquals(first.getNodeToCommunity().get("y"), first.getNodeToCommunity().get("z"));
        assertFalse(first.getNodeToCommunity().get("a").equals(first.getNodeToCommunity().get("x")));
        // 社区ID = c_ + 簇内胜出标签（并列取最小标签的确定性结果）
        assertEquals("c_b", first.getNodeToCommunity().get("a"));
        assertEquals("c_y", first.getNodeToCommunity().get("z"));
        assertEquals(2, first.getCommunities().size());
    }

    @Test
    void 迭代上限出口标记未收敛() {
        // 长链震荡受限：上限 1 轮大概率未收敛
        List<WeightedEdgeVO> chain = Arrays.asList(edge("n1", "n2"), edge("n2", "n3"),
                edge("n3", "n4"), edge("n4", "n5"));
        CommunityPartitionVO result = detector.detect(
                Arrays.asList("n1", "n2", "n3", "n4", "n5"), chain, 1);
        assertFalse(result.isConverged());
        assertEquals(1, result.getIterations());
    }

    @Test
    void 孤立节点自成社区() {
        CommunityPartitionVO result = detector.detect(
                Arrays.asList("solo", "a", "b"),
                Collections.singletonList(edge("a", "b")), 10);
        assertEquals("c_solo", result.getNodeToCommunity().get("solo"));
        assertEquals(1, result.getCommunities().get("c_solo").size());
        // a-b 单边：确定性地归入同一社区（并列最小标签语义下为 c_b）
        assertEquals(result.getNodeToCommunity().get("a"), result.getNodeToCommunity().get("b"));
        assertEquals("c_b", result.getNodeToCommunity().get("b"));
    }

    @Test
    void 空图与非法输入边界() {
        CommunityPartitionVO empty = detector.detect(Collections.emptyList(), Collections.emptyList(), 5);
        assertTrue(empty.getCommunities().isEmpty());
        assertTrue(empty.getNodeToCommunity().isEmpty());
        assertTrue(empty.isConverged());
        // 两点单边：同标签
        CommunityPartitionVO pair = detector.detect(Arrays.asList("p", "q"),
                Collections.singletonList(edge("p", "q")), 5);
        assertEquals(pair.getNodeToCommunity().get("p"), pair.getNodeToCommunity().get("q"));
        // 非法迭代上限 / 非法边与节点被忽略
        assertThrows(IllegalArgumentException.class, () -> detector.detect(
                Collections.singletonList("a"), Collections.emptyList(), 0));
        CommunityPartitionVO filtered = detector.detect(
                Arrays.asList("a", "b"),
                Arrays.asList(edge("a", "a"), edge("a", "ghost"), WeightedEdgeVO.builder()
                        .sourceKey("a").targetKey("b").weight(0).build()),
                5);
        // 自环/未知端点/零权边全忽略 → a、b 各自成社区
        assertEquals(2, filtered.getCommunities().size());
    }
}

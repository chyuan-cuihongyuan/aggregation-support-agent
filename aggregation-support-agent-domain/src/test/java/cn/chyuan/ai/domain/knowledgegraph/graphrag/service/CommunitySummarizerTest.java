package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.adapter.port.ICommunitySummaryPort;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunitySummaryTreeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunitySummaryVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphNodeVO;
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
 * 社区分层摘要装配器单测（工单 0308 AM3）：模板兜底/端口优先/层级聚合/边界。
 */
class CommunitySummarizerTest {

    private GraphIndexVO sampleIndex() {
        return GraphIndexVO.builder()
                .documentId("doc")
                .textUnits(Collections.emptyList())
                .nodes(Arrays.asList(node("a"), node("b"), node("c")))
                .edges(Arrays.asList(edge("a", "b"), edge("b", "c")))
                .entitySources(Collections.emptyMap())
                .relationSources(Collections.emptyMap())
                .build();
    }

    private CommunityPartitionVO partitionOf(String... members) {
        Map<String, List<String>> communities = new LinkedHashMap<>();
        Map<String, String> nodeToCommunity = new LinkedHashMap<>();
        for (String member : members) {
            communities.computeIfAbsent("c_" + member, k -> new ArrayList<>()).add(member);
            nodeToCommunity.put(member, "c_" + member);
        }
        return CommunityPartitionVO.builder()
                .nodeToCommunity(nodeToCommunity)
                .communities(communities)
                .iterations(1)
                .converged(true)
                .build();
    }

    /** 单社区多成员夹具（模板摘要断言用） */
    private CommunityPartitionVO singleCommunity(String id, String... members) {
        Map<String, List<String>> communities = new LinkedHashMap<>();
        communities.put(id, Arrays.asList(members));
        Map<String, String> nodeToCommunity = new LinkedHashMap<>();
        for (String member : members) {
            nodeToCommunity.put(member, id);
        }
        return CommunityPartitionVO.builder()
                .nodeToCommunity(nodeToCommunity)
                .communities(communities)
                .iterations(1)
                .converged(true)
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
    void 模板兜底摘要含成员与内部关系数() {
        CommunitySummarizer summarizer = new CommunitySummarizer(4);
        CommunitySummaryTreeVO tree = summarizer.summarize(
                sampleIndex(), singleCommunity("c_a", "a", "b", "c"), null);
        assertEquals(1, tree.getLevelCount());
        CommunitySummaryVO c0 = tree.getLevels().get(0).get(0);
        assertEquals("c_a", c0.getCommunityId());
        assertTrue(c0.getSummaryText().contains("a、b、c"), "模板摘要应含成员清单");
        assertTrue(c0.getSummaryText().contains("2 条"), "模板摘要应含内部关系数");
        assertEquals(3, c0.getMemberCount());
    }

    @Test
    void 端口输出优先且异常回落模板() {
        CommunitySummarizer summarizer = new CommunitySummarizer(4);
        ICommunitySummaryPort port = (communityId, members, relationCount) -> {
            if (communityId.equals("c_a")) {
                return "端口摘要：三角簇。";
            }
            throw new IllegalStateException("LLM 不可用");
        };
        CommunitySummaryTreeVO tree = summarizer.summarize(
                sampleIndex(), singleCommunity("c_a", "a", "b", "c"), port);
        assertEquals("端口摘要：三角簇。", tree.getLevels().get(0).get(0).getSummaryText());
        // 端口返回 null 也走兜底
        ICommunitySummaryPort nullPort = (communityId, members, relationCount) -> null;
        CommunitySummaryTreeVO nullTree = summarizer.summarize(
                sampleIndex(), singleCommunity("c_a", "a", "b", "c"), nullPort);
        assertTrue(nullTree.getLevels().get(0).get(0).getSummaryText().contains("成员实体"));
    }

    @Test
    void 超过扇出才逐层聚合且叶子计数守恒() {
        CommunitySummarizer summarizer = new CommunitySummarizer(2);
        CommunitySummaryTreeVO tree = summarizer.summarize(
                sampleIndex(), partitionOf("n1", "n2", "n3", "n4", "n5"), null);
        // 5 社区 → C1 = ceil(5/2)=3 桶 → 3 > 2 → C2 = ceil(3/2)=2 桶
        assertEquals(3, tree.getLevelCount());
        assertEquals(5, tree.getLevels().get(0).size());
        assertEquals(3, tree.getLevels().get(1).size());
        assertEquals(2, tree.getLevels().get(2).size());
        int leafAtC2 = tree.getLevels().get(2).stream().mapToInt(CommunitySummaryVO::getMemberCount).sum();
        assertEquals(5, leafAtC2, "聚合层叶子计数应守恒");
        assertEquals("agg_l1_0", tree.getLevels().get(1).get(0).getCommunityId());
    }

    @Test
    void 扇出边界与非法配置() {
        CommunitySummarizer summarizer = new CommunitySummarizer(4);
        // 4 社区 = 扇出，不聚合
        CommunitySummaryTreeVO flat = summarizer.summarize(
                sampleIndex(), partitionOf("n1", "n2", "n3", "n4"), null);
        assertEquals(1, flat.getLevelCount());
        // 单社区单层
        CommunitySummaryTreeVO single = summarizer.summarize(sampleIndex(), partitionOf("a"), null);
        assertEquals(1, single.getLevelCount());
        assertThrows(IllegalArgumentException.class, () -> new CommunitySummarizer(0));
    }
}

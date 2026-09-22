package cn.chyuan.ai.domain.graphkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图端口组合管线 BN8 单测（工单 0556）：
 * 图谱导出只读联动构建子图 + 拓扑→分量→PageRank 串联管线。
 */
class GraphPortPipelineTest {

    @Test
    void BN8_图谱导出只读联动构建子图() {
        GraphPort.InMemoryGraphEngine engine = new GraphPort.InMemoryGraphEngine();
        // 模拟 knowledgegraph 导出：节点 1/2/3 连通，节点 9 为外部噪音边
        Graph sub = engine.fromKnowledgeGraphExport(false, Set.of(1, 2, 3),
                List.of(new int[]{1, 2}, new int[]{2, 3}, new int[]{3, 1}, new int[]{3, 9}));
        assertEquals(3, sub.order(), "导出集外节点裁剪");
        assertEquals(3, sub.size(), "三角形三边全保留");
        assertEquals(Set.of(1, 2, 3), Set.copyOf(sub.nodes()));
    }

    @Test
    void BN8_组合管线_拓扑分量PageRank串联() {
        GraphPort.InMemoryGraphEngine engine = new GraphPort.InMemoryGraphEngine();
        Graph dag = new Graph(true);
        dag.addEdge(1, 2);
        dag.addEdge(1, 3);
        dag.addEdge(2, 3);
        GraphPort.PipelineResult result = engine.pipeline(dag);
        assertTrue(result.topo().isDag(), "管线第一步拓扑校验");
        assertEquals(List.of(1, 2, 3), result.topo().order());
        assertEquals(1, result.components().values().stream().distinct().count(), "弱连通单分量");
        assertEquals(3, result.topRanked().node(), "汇聚节点双入边得分最高");
        assertTrue(result.topRanked().score() > 0.3d);
    }

    @Test
    void BN8_有环图管线保留分量与PageRank能力() {
        GraphPort.InMemoryGraphEngine engine = new GraphPort.InMemoryGraphEngine();
        Graph cyclic = new Graph(true);
        cyclic.addEdge(1, 2);
        cyclic.addEdge(2, 3);
        cyclic.addEdge(3, 1);
        cyclic.addEdge(1, 4);
        GraphPort.PipelineResult result = engine.pipeline(cyclic);
        assertTrue(!result.topo().isDag(), "环图拓扑不通过但管线继续给出分量与排名");
        assertEquals(List.of(1, 2, 3, 4), result.topo().cycleNodes(), "环上及下游节点全部未消解");
        assertEquals(1, result.components().values().stream().distinct().count());
        assertEquals(1, result.topRanked().node(), "环上等分节点取最小 id");
    }
}

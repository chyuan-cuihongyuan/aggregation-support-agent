package cn.chyuan.ai.domain.graphkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图算法内核 BN1-BN7 单测（工单 0549-0555）：
 * 图结构遍历/拓扑环检测/并查集分量/Dijkstra/PageRank/中心度三口径/标签传播社区。
 */
class GraphKernelTest {

    private Graph diamond() {
        Graph g = new Graph(false);
        g.addEdge(1, 2);
        g.addEdge(1, 3);
        g.addEdge(2, 4);
        g.addEdge(3, 4);
        return g;
    }

    @Test
    void BN1_图结构与BFS_DFS遍历() {
        Graph g = diamond();
        assertEquals(4, g.order());
        assertEquals(4, g.size());
        assertEquals(Map.of(2, 1.0d, 3, 1.0d), g.neighbors(1));
        assertEquals(List.of(1, 2, 3, 4), g.bfs(1), "BFS 层序邻居升序");
        assertEquals(List.of(1, 2, 4, 3), g.dfs(1), "DFS 先序邻居升序");
        assertEquals(List.of(), g.bfs(99), "未注册节点空遍历");
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(5, 5), "自环拒绝");
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(1, 2), "平行边拒绝");
        assertThrows(IllegalArgumentException.class, () -> g.neighbors(99), "节点不存在拒绝");
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(1, 4, -2.0d), "负权边拒绝");
        Graph sub = g.subgraph(java.util.Set.of(1, 3, 4));
        assertEquals(3, sub.order(), "导出子图只保留两端在集内");
        assertEquals(2, sub.size(), "原 (3,4) 边保留，(1,x) 边裁剪");
    }

    @Test
    void BN2_拓扑排序与环检测() {
        Graph dag = new Graph(true);
        dag.addEdge(1, 2);
        dag.addEdge(1, 3);
        dag.addEdge(2, 4);
        dag.addEdge(3, 4);
        TopoSort.Result result = TopoSort.sort(dag);
        assertTrue(result.isDag());
        assertEquals(List.of(1, 2, 3, 4), result.order(), "同层小 id 先出确定性");
        Graph cyclic = new Graph(true);
        cyclic.addEdge(1, 2);
        cyclic.addEdge(2, 3);
        cyclic.addEdge(3, 1);
        cyclic.addNode(4);
        TopoSort.Result cycled = TopoSort.sort(cyclic);
        assertEquals(List.of(4), cycled.order(), "有环时输出部分序");
        assertEquals(List.of(1, 2, 3), cycled.cycleNodes(), "环上及下游节点报告");
        assertThrows(IllegalArgumentException.class, () -> TopoSort.sort(diamond()), "无向图拒绝");
        assertThrows(IllegalArgumentException.class, () -> TopoSort.requireDag(cyclic), "DAG 断言拒绝环图");
    }

    @Test
    void BN3_并查集分量与合并序列无关() {
        Graph g = new Graph(false);
        g.addEdge(1, 2);
        g.addEdge(3, 4);
        g.addNode(5);
        UnionFind uf = new UnionFind(g.nodes());
        uf.union(1, 2);
        uf.union(3, 4);
        assertEquals(3, uf.componentCount(), "三个分量（5 孤立自成分量）");
        assertEquals(1, uf.components().get(1));
        assertEquals(1, uf.components().get(2), "代表元取分量内最小 id");
        assertEquals(3, uf.components().get(4));
        assertTrue(uf.connected(3, 4));
        UnionFind reverse = new UnionFind(g.nodes());
        reverse.union(4, 3);
        reverse.union(2, 1);
        assertEquals(uf.components(), reverse.components(), "合并序列无关");
        UnionFind uf2 = new UnionFind(g.nodes());
        assertThrows(IllegalArgumentException.class, () -> uf2.find(99), "未注册节点拒绝");
    }

    @Test
    void BN4_Dijkstra最短路与路径重建() {
        Graph g = new Graph(true);
        g.addEdge(1, 2, 1);
        g.addEdge(2, 3, 2);
        g.addEdge(1, 3, 5);
        g.addNode(9);
        Map<Integer, Double> dist = Dijkstra.distances(g, 1);
        assertEquals(0.0d, dist.get(1));
        assertEquals(1.0d, dist.get(2));
        assertEquals(3.0d, dist.get(3), "走 1→2→3 更短");
        assertEquals(Dijkstra.INF, dist.get(9), "不可达正无穷");
        Dijkstra.Path path = Dijkstra.shortestPath(g, 1, 3);
        assertEquals(3.0d, path.distance());
        assertEquals(List.of(1, 2, 3), path.path(), "前驱路径重建");
        Dijkstra.Path self = Dijkstra.shortestPath(g, 1, 1);
        assertEquals(0.0d, self.distance());
        assertEquals(List.of(1), self.path());
        Dijkstra.Path unreachable = Dijkstra.shortestPath(g, 1, 9);
        assertEquals(List.of(), unreachable.path(), "不可达空路径");
        assertThrows(IllegalArgumentException.class, () -> Dijkstra.shortestPath(g, 1, 99), "端点不在图拒绝");
    }

    @Test
    void BN5_PageRank幂迭代与悬挂节点() {
        Graph cycle = new Graph(true);
        cycle.addEdge(1, 2);
        cycle.addEdge(2, 3);
        cycle.addEdge(3, 1);
        List<PageRank.Rank> ranks = PageRank.ranks(cycle, 0.85d, 1.0E-12d, 200);
        double sum = ranks.stream().mapToDouble(PageRank.Rank::score).sum();
        assertEquals(1.0d, sum, 1.0E-9, "分数和归一为 1");
        for (PageRank.Rank rank : ranks) {
            assertEquals(1.0d / 3.0d, rank.score(), 1.0E-6, "对称环等分");
        }
        Graph dangling = new Graph(true);
        dangling.addEdge(1, 2);
        List<PageRank.Rank> withDangling = PageRank.ranks(dangling, 0.85d, 1.0E-12d, 200);
        assertEquals(1.0d, withDangling.stream().mapToDouble(PageRank.Rank::score).sum(), 1.0E-9,
                "悬挂节点质量均摊守恒");
        assertTrue(withDangling.get(0).node() == 2, "悬挂汇点得分最高");
        assertThrows(IllegalArgumentException.class, () -> PageRank.ranks(cycle, 1.0d, 1.0E-9d, 10),
                "阻尼越界拒绝");
        assertThrows(IllegalArgumentException.class, () -> PageRank.ranks(new Graph(true), 0.85d,
                1.0E-9d, 10), "空图拒绝");
    }

    @Test
    void BN6_中心度三口径() {
        Graph star = new Graph(false);
        star.addEdge(0, 1);
        star.addEdge(0, 2);
        star.addEdge(0, 3);
        Map<Integer, Double> degree = Centrality.degree(star);
        assertEquals(1.0d, degree.get(0), 1.0E-12, "星形中心度中心性为 1");
        assertEquals(1.0d / 3.0d, degree.get(1), 1.0E-12);
        Map<Integer, Double> closeness = Centrality.closeness(star);
        assertEquals(1.0d, closeness.get(0), 1.0E-12, "中心接近中心性 (n-1)/Σd=1");
        assertEquals(0.6d, closeness.get(2), 1.0E-12, "叶接近中心性 3/5");
        Map<Integer, Double> between = Centrality.betweenness(star);
        assertEquals(3.0d, between.get(0), 1.0E-9, "星形中心介数 C(3,2)=3");
        assertEquals(0.0d, between.get(1), 1.0E-9, "叶介数为 0");
    }

    @Test
    void BN7_标签传播社区与模块度() {
        Graph bridge = new Graph(false);
        int[][] edges = {{1, 2}, {2, 3}, {1, 3}, {4, 5}, {5, 6}, {4, 6}, {3, 4}};
        for (int[] edge : edges) {
            bridge.addEdge(edge[0], edge[1]);
        }
        Map<Integer, Integer> labels = Community.labelPropagation(bridge, 50);
        assertEquals(labels.get(1), labels.get(2));
        assertEquals(labels.get(2), labels.get(3));
        assertEquals(labels.get(4), labels.get(5));
        assertEquals(labels.get(5), labels.get(6));
        assertTrue(!labels.get(1).equals(labels.get(4)), "两三角分属两社区");
        Map<Integer, Integer> again = Community.labelPropagation(bridge, 50);
        assertEquals(labels, again, "同输入同划分确定性");
        double q = Community.modularity(bridge, labels);
        assertEquals(0.3571428571428571d, q, 1.0E-6, "桥接双三角模块度 5/14");
        assertThrows(IllegalArgumentException.class, () -> Community.labelPropagation(
                new Graph(true), 10), "有向图拒绝");
    }
}

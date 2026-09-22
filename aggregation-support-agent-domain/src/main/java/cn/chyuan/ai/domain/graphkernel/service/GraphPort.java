package cn.chyuan.ai.domain.graphkernel.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 图端口+组合管线（工单 0556 BN8）。
 * GraphPort（构图→算法→结果）+组合管线（拓扑校验→分量→PageRank 串联）+
 * 与 knowledgegraph 图谱只读联动（从图谱导出边集构建子图计算，不改图谱）/
 * graph-kernel.enabled 默认关（开启才改变行为）。
 */
public interface GraphPort {

    /** 从图谱导出的边集构建计算子图（只读联动：knowledgegraph 侧导出，本侧不回写） */
    Graph fromKnowledgeGraphExport(boolean directed, Set<Integer> nodes, List<int[]> edges);

    /** 组合管线结果 */
    record PipelineResult(TopoSort.Result topo, Map<Integer, Integer> components,
            PageRank.Rank topRanked) {
    }

    /** 组合管线：拓扑校验（DAG 置信）→ 弱连通分量 → PageRank 串联 */
    PipelineResult pipeline(Graph g);

    /** 内存假实现：全算法内核组合 */
    class InMemoryGraphEngine implements GraphPort {

        @Override
        public Graph fromKnowledgeGraphExport(boolean directed, Set<Integer> nodes, List<int[]> edges) {
            Graph g = new Graph(directed);
            for (Integer node : new TreeMap<>(nodesOf(nodes)).keySet()) {
                g.addNode(node);
            }
            for (int[] edge : edges) {
                if (nodes.contains(edge[0]) && nodes.contains(edge[1])) {
                    g.addEdge(edge[0], edge[1]);
                }
            }
            return g;
        }

        private Map<Integer, Integer> nodesOf(Set<Integer> nodes) {
            Map<Integer, Integer> out = new TreeMap<>();
            int i = 0;
            for (Integer node : nodes) {
                out.put(node, i++);
            }
            return out;
        }

        @Override
        public PipelineResult pipeline(Graph g) {
            TopoSort.Result topo = TopoSort.sort(g);
            Map<Integer, Integer> components = UnionFind.of(g);
            PageRank.Rank top = PageRank.ranks(g, 0.85d, 1.0E-9d, 100).get(0);
            return new PipelineResult(topo, components, top);
        }
    }
}

package cn.chyuan.ai.domain.graphkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 连通分量与并查集（工单 0551 BN3，networkx connected_components 思想）。
 * 路径压缩+按秩合并/弱连通分量聚合（无向化）/分量计数与代表元（分量内最小 id）/
 * 合并序列无关性（最终分量划分恒定）/孤立项自成分量。
 */
public final class UnionFind {

    private final Map<Integer, Integer> parent = new TreeMap<>();
    private final Map<Integer, Integer> rank = new TreeMap<>();

    public UnionFind(Iterable<Integer> nodes) {
        for (Integer node : nodes) {
            parent.put(node, node);
            rank.put(node, 0);
        }
    }

    /** 查找（路径压缩；未注册节点拒绝） */
    public int find(int x) {
        Integer self = parent.get(x);
        if (self == null) {
            throw new IllegalArgumentException("节点未注册: " + x);
        }
        if (self != x) {
            parent.put(x, find(self));
        }
        return parent.get(x);
    }

    /** 合并（按秩合并；已在同分量返回 false） */
    public boolean union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra == rb) {
            return false;
        }
        int rankA = rank.get(ra);
        int rankB = rank.get(rb);
        if (rankA < rankB) {
            parent.put(ra, rb);
        } else if (rankA > rankB) {
            parent.put(rb, ra);
        } else {
            parent.put(rb, ra);
            rank.merge(ra, 1, Integer::sum);
        }
        return true;
    }

    /** 连通判定 */
    public boolean connected(int a, int b) {
        return find(a) == find(b);
    }

    /** 分量映射：节点 → 代表元（代表元取分量内最小 id，TreeMap 节点序确定） */
    public Map<Integer, Integer> components() {
        Map<Integer, Integer> minByRoot = new TreeMap<>();
        for (Integer node : parent.keySet()) {
            minByRoot.merge(find(node), node, Integer::min);
        }
        Map<Integer, Integer> out = new TreeMap<>();
        for (Integer node : parent.keySet()) {
            out.put(node, minByRoot.get(find(node)));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** 分量计数 */
    public int componentCount() {
        return (int) components().values().stream().distinct().count();
    }

    /** 图弱连通分量（无向化合并全部边） */
    public static Map<Integer, Integer> of(Graph g) {
        UnionFind uf = new UnionFind(g.nodes());
        for (Integer u : g.nodes()) {
            for (Integer v : g.neighbors(u).keySet()) {
                uf.union(u, v);
            }
        }
        return uf.components();
    }
}

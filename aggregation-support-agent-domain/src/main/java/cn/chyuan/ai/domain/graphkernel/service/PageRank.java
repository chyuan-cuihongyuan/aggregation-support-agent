package cn.chyuan.ai.domain.graphkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PageRank（工单 0553 BN5，networkx pagerank 思想）。
 * 阻尼系数幂迭代/收敛阈值与迭代上限双重停止/分数和归一为 1/
 * 出边均分质量/悬挂节点质量均摊/得分降序同分 id 升序输出。
 */
public final class PageRank {

    /** 排名行（得分降序、同分 id 升序） */
    public record Rank(int node, double score) {
    }

    public static List<Rank> ranks(Graph g, double damping, double tolerance, int maxIter) {
        requireParams(g, damping, tolerance, maxIter);
        int n = g.order();
        List<Integer> nodes = g.nodes();
        double[] rank = new double[n];
        java.util.Arrays.fill(rank, 1.0d / n);
        // 出度与悬挂节点清单
        Map<Integer, Double> outDeg = new TreeMap<>();
        List<Integer> dangling = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int node = nodes.get(i);
            int deg = g.neighbors(node).size();
            outDeg.put(node, (double) deg);
            if (deg == 0) {
                dangling.add(i);
            }
        }
        for (int iter = 0; iter < maxIter; iter++) {
            double danglingMass = 0.0d;
            for (int i : dangling) {
                danglingMass += rank[i];
            }
            double base = (1.0d - damping) / n + damping * danglingMass / n;
            double[] next = new double[n];
            java.util.Arrays.fill(next, base);
            for (int i = 0; i < n; i++) {
                int u = nodes.get(i);
                if (outDeg.get(u) == 0.0d) {
                    continue;
                }
                double share = damping * rank[i] / outDeg.get(u);
                for (Integer v : g.neighbors(u).keySet()) {
                    next[nodes.indexOf(v)] += share;
                }
            }
            double delta = 0.0d;
            for (int i = 0; i < n; i++) {
                delta += Math.abs(next[i] - rank[i]);
            }
            rank = next;
            if (delta < tolerance) {
                break;
            }
        }
        // 归一为和 1（数值误差兜底）
        double sum = 0.0d;
        for (double v : rank) {
            sum += v;
        }
        List<Rank> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Rank(nodes.get(i), rank[i] / sum));
        }
        out.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            return byScore != 0 ? byScore : Integer.compare(a.node(), b.node());
        });
        return java.util.Collections.unmodifiableList(out);
    }

    private static void requireParams(Graph g, double damping, double tolerance, int maxIter) {
        if (g.order() == 0) {
            throw new IllegalArgumentException("空图拒绝");
        }
        if (damping <= 0.0d || damping >= 1.0d) {
            throw new IllegalArgumentException("阻尼系数须在 (0,1): " + damping);
        }
        if (tolerance <= 0.0d) {
            throw new IllegalArgumentException("收敛阈值须为正");
        }
        if (maxIter < 1) {
            throw new IllegalArgumentException("迭代上限须≥1");
        }
    }
}

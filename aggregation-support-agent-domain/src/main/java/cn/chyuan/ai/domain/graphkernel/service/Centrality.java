package cn.chyuan.ai.domain.graphkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 中心度（工单 0554 BN6，networkx centrality 思想）。
 * 度中心性（度数/(n-1) 归一）/接近中心性（BFS 距离倒数和，连通口径 (n-1)/Σd）/
 * 介数中心性（Brandes 无权依赖累加，原始值）/三口径排序输出/孤立节点零分。
 */
public final class Centrality {

    private Centrality() {
    }

    /** 度中心性：度数/(n-1)（有向图用出度+入度合计，无向图为邻居数） */
    public static Map<Integer, Double> degree(Graph g) {
        int n = g.order();
        Map<Integer, Double> out = new TreeMap<>();
        for (Integer node : g.nodes()) {
            double deg = g.neighbors(node).size();
            if (!g.directed()) {
                out.put(node, n > 1 ? deg / (n - 1) : 0.0d);
            } else {
                long inDeg = 0;
                for (Integer u : g.nodes()) {
                    if (g.neighbors(u).containsKey(node)) {
                        inDeg++;
                    }
                }
                out.put(node, n > 1 ? (deg + inDeg) / (double) (n - 1) : 0.0d);
            }
        }
        return out;
    }

    /** 接近中心性：(n-1)/Σ最短距离（连通口径；不可达节点按 INF 排除，全不可达为 0） */
    public static Map<Integer, Double> closeness(Graph g) {
        int n = g.order();
        Map<Integer, Double> out = new TreeMap<>();
        for (Integer node : g.nodes()) {
            Map<Integer, Double> dist = Dijkstra.distances(g, node);
            double sum = 0.0d;
            int reachable = 0;
            for (Map.Entry<Integer, Double> entry : dist.entrySet()) {
                if (entry.getValue() != Dijkstra.INF) {
                    sum += entry.getValue();
                    reachable++;
                }
            }
            reachable--; // 扣除自身
            out.put(node, reachable > 0 && sum > 0 ? reachable / (double) sum : 0.0d);
        }
        return out;
    }

    /** 介数中心性：Brandes 无权版（BFS 最短路径 DAG 依赖累加，原始值不归一） */
    public static Map<Integer, Double> betweenness(Graph g) {
        Map<Integer, Double> between = new TreeMap<>();
        for (Integer node : g.nodes()) {
            between.put(node, 0.0d);
        }
        for (Integer source : g.nodes()) {
            // 单源 BFS：sigma 最短路径计数、依赖回溯
            List<Integer> order = new ArrayList<>();
            Map<Integer, List<Integer>> preds = new TreeMap<>();
            Map<Integer, Double> sigma = new TreeMap<>();
            Map<Integer, Double> dist = new TreeMap<>();
            for (Integer node : g.nodes()) {
                preds.put(node, new ArrayList<>());
                sigma.put(node, 0.0d);
                dist.put(node, -1.0d);
            }
            sigma.put(source, 1.0d);
            dist.put(source, 0.0d);
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                order.add(cur);
                for (Integer next : g.neighbors(cur).keySet()) {
                    if (dist.get(next) < 0) {
                        dist.put(next, dist.get(cur) + 1);
                        queue.add(next);
                    }
                    if (dist.get(next) == dist.get(cur) + 1) {
                        sigma.put(next, sigma.get(next) + sigma.get(cur));
                        preds.get(next).add(cur);
                    }
                }
            }
            Map<Integer, Double> delta = new TreeMap<>();
            for (Integer node : g.nodes()) {
                delta.put(node, 0.0d);
            }
            for (int i = order.size() - 1; i >= 0; i--) {
                int w = order.get(i);
                for (Integer v : preds.get(w)) {
                    double share = sigma.get(v) / sigma.get(w) * (1 + delta.get(w));
                    delta.put(v, delta.get(v) + share);
                }
                if (w != source) {
                    between.put(w, between.get(w) + delta.get(w));
                }
            }
        }
        if (!g.directed()) {
            // 无向图 Brandes 原始值除 2
            for (Map.Entry<Integer, Double> entry : between.entrySet()) {
                between.put(entry.getKey(), entry.getValue() / 2.0d);
            }
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(between));
    }
}

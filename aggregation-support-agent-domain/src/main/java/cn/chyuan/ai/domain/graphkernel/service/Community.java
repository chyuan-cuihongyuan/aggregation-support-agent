package cn.chyuan.ai.domain.graphkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 社区发现（工单 0555 BN7，networkx 社区思想）。
 * 标签传播（同步更新：多数邻居标签；平局含当前标签则保留否则取最小）/
 * 收敛或迭代上限停止/模块度 Q（边内聚-期望）/同输入同划分确定性。
 */
public final class Community {

    private Community() {
    }

    /** 标签传播：节点 → 社区标签（社区标签=初始代表 id） */
    public static Map<Integer, Integer> labelPropagation(Graph g, int maxIter) {
        if (g.directed()) {
            throw new IllegalArgumentException("标签传播要求无向图");
        }
        if (maxIter < 1) {
            throw new IllegalArgumentException("迭代上限须≥1");
        }
        Map<Integer, Integer> labels = new TreeMap<>();
        for (Integer node : g.nodes()) {
            labels.put(node, node);
        }
        for (int iter = 0; iter < maxIter; iter++) {
            Map<Integer, Integer> next = new TreeMap<>();
            boolean changed = false;
            // 同步更新：全部基于上一轮快照
            for (Integer node : g.nodes()) {
                Map<Integer, Integer> counts = new TreeMap<>();
                for (Integer nbr : g.neighbors(node).keySet()) {
                    counts.merge(labels.get(nbr), 1, Integer::sum);
                }
                if (counts.isEmpty()) {
                    next.put(node, labels.get(node));
                    continue;
                }
                int maxCount = counts.values().stream().max(Integer::compareTo).orElse(0);
                List<Integer> candidates = new ArrayList<>();
                for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                    if (entry.getValue() == maxCount) {
                        candidates.add(entry.getKey());
                    }
                }
                int current = labels.get(node);
                int chosen = candidates.contains(current) ? current
                        : candidates.stream().min(Integer::compareTo).orElse(current);
                next.put(node, chosen);
                if (chosen != current) {
                    changed = true;
                }
            }
            labels = next;
            if (!changed) {
                break;
            }
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    /** 模块度 Q=Σ_r [in_r/m - (deg_r/2m)²]（无向图口径） */
    public static double modularity(Graph g, Map<Integer, Integer> labels) {
        if (g.directed()) {
            throw new IllegalArgumentException("模块度要求无向图");
        }
        double m = g.size();
        if (m == 0) {
            throw new IllegalArgumentException("无边图模块度未定义");
        }
        Map<Integer, Double> inEdges = new TreeMap<>();
        Map<Integer, Double> degreeSum = new TreeMap<>();
        for (Integer node : g.nodes()) {
            int community = labels.get(node);
            double deg = g.neighbors(node).size();
            degreeSum.merge(community, deg, Double::sum);
            for (Integer nbr : g.neighbors(node).keySet()) {
                if (labels.get(nbr).intValue() == community && nbr >= node) {
                    inEdges.merge(community, 1.0d, Double::sum);
                }
            }
        }
        double q = 0.0d;
        for (Integer community : inEdges.keySet()) {
            double in = inEdges.getOrDefault(community, 0.0d);
            double degSum = degreeSum.getOrDefault(community, 0.0d);
            q += in / m - Math.pow(degSum / (2 * m), 2);
        }
        return q;
    }
}

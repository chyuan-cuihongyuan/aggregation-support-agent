package cn.chyuan.ai.domain.graphkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * 拓扑排序与环检测（工单 0550 BN2，networkx topological_sort 思想）。
 * Kahn 入度消零（同层节点 id 小顶堆保证确定性）/环存在时返回环上及下游节点集/
 * DAG 断言/非有向图拒绝。
 */
public final class TopoSort {

    /** 排序结果：order 为拓扑序（有环时为部分序）；cycleNodes 非空即有环 */
    public record Result(List<Integer> order, List<Integer> cycleNodes) {
        public boolean isDag() {
            return cycleNodes.isEmpty();
        }
    }

    public static Result sort(Graph g) {
        if (!g.directed()) {
            throw new IllegalArgumentException("拓扑排序要求有向图");
        }
        Map<Integer, Double> indegree = new TreeMap<>();
        for (Integer node : g.nodes()) {
            indegree.put(node, 0.0d);
        }
        for (Integer u : g.nodes()) {
            for (Integer v : g.neighbors(u).keySet()) {
                indegree.merge(v, 1.0d, Double::sum);
            }
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (Map.Entry<Integer, Double> entry : indegree.entrySet()) {
            if (entry.getValue() == 0.0d) {
                ready.add(entry.getKey());
            }
        }
        List<Integer> order = new ArrayList<>(g.order());
        while (!ready.isEmpty()) {
            int cur = ready.poll();
            order.add(cur);
            for (Integer next : g.neighbors(cur).keySet()) {
                double remaining = indegree.merge(next, -1.0d, Double::sum);
                if (remaining == 0.0d) {
                    ready.add(next);
                }
            }
        }
        List<Integer> cycleNodes = new ArrayList<>();
        if (order.size() < g.order()) {
            for (Map.Entry<Integer, Double> entry : indegree.entrySet()) {
                if (entry.getValue() > 0.0d) {
                    cycleNodes.add(entry.getKey());
                }
            }
        }
        return new Result(List.copyOf(order), List.copyOf(cycleNodes));
    }

    /** DAG 断言（有环抛出） */
    public static void requireDag(Graph g) {
        Result result = sort(g);
        if (!result.isDag()) {
            throw new IllegalArgumentException("存在环: " + result.cycleNodes());
        }
    }
}

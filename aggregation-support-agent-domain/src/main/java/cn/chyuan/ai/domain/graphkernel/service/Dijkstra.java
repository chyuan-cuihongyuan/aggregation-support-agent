package cn.chyuan.ai.domain.graphkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * 最短路径（工单 0552 BN4，networkx Dijkstra 思想）。
 * 非负权单源最短距离/前驱数组路径重建/不可达标记（正无穷+空路径）/
 * 负权边拒绝/同距按 id 小顶堆确定性。
 */
public final class Dijkstra {

    public static final double INF = Double.POSITIVE_INFINITY;

    /** 路径结果（不可达时 distance=INF、path 空） */
    public record Path(double distance, List<Integer> path) {
    }

    /** 单源最短距离（源点 0，不可达 INF；负权拒绝） */
    public static Map<Integer, Double> distances(Graph g, int source) {
        requireNonNegative(g);
        Map<Integer, Double> dist = new TreeMap<>();
        for (Integer node : g.nodes()) {
            dist.put(node, INF);
        }
        dist.put(source, 0.0d);
        // 队列条目 {节点 id, 距离}（同距按 id 确定性）
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> {
            int byDist = Double.compare(a[1], b[1]);
            return byDist != 0 ? byDist : Double.compare(a[0], b[0]);
        });
        queue.add(new double[]{source, 0.0d});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int cur = (int) top[0];
            if (top[1] > dist.get(cur)) {
                continue;
            }
            for (Map.Entry<Integer, Double> edge : g.neighbors(cur).entrySet()) {
                double candidate = dist.get(cur) + edge.getValue();
                if (candidate < dist.get(edge.getKey())) {
                    dist.put(edge.getKey(), candidate);
                    queue.add(new double[]{edge.getKey(), candidate});
                }
            }
        }
        return dist;
    }

    /** 最短路径重建（source==target 零距离；不可达空路径） */
    public static Path shortestPath(Graph g, int source, int target) {
        if (!g.nodes().contains(source) || !g.nodes().contains(target)) {
            throw new IllegalArgumentException("端点不在图中");
        }
        Map<Integer, Double> dist = new TreeMap<>();
        Map<Integer, Integer> prev = new TreeMap<>();
        for (Integer node : g.nodes()) {
            dist.put(node, INF);
        }
        dist.put(source, 0.0d);
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> {
            int byDist = Double.compare(a[1], b[1]);
            return byDist != 0 ? byDist : Double.compare(a[0], b[0]);
        });
        queue.add(new double[]{source, 0.0d});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int cur = (int) top[0];
            if (top[1] > dist.get(cur)) {
                continue;
            }
            if (cur == target) {
                break;
            }
            for (Map.Entry<Integer, Double> edge : g.neighbors(cur).entrySet()) {
                double candidate = dist.get(cur) + edge.getValue();
                if (candidate < dist.get(edge.getKey())) {
                    dist.put(edge.getKey(), candidate);
                    prev.put(edge.getKey(), cur);
                    queue.add(new double[]{edge.getKey(), candidate});
                }
            }
        }
        if (dist.get(target) == INF) {
            return new Path(INF, List.of());
        }
        List<Integer> path = new ArrayList<>();
        Integer walk = target;
        while (walk != null) {
            path.add(0, walk);
            walk = prev.get(walk);
        }
        return new Path(dist.get(target), List.copyOf(path));
    }

    private static void requireNonNegative(Graph g) {
        for (Integer u : g.nodes()) {
            for (Map.Entry<Integer, Double> edge : g.neighbors(u).entrySet()) {
                if (edge.getValue() < 0) {
                    throw new IllegalArgumentException("负权边拒绝: " + u + "->" + edge.getKey());
                }
            }
        }
    }
}

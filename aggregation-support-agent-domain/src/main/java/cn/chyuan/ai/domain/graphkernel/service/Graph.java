package cn.chyuan.ai.domain.graphkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 图结构与遍历（工单 0549 BN1，networkx 邻接表思想）。
 * 有向/无向邻接表（TreeMap 保序）/自环与平行边拒绝/BFS 层序与 DFS 先序
 * （邻居按 id 升序保证确定性）/节点边基数/导出子图（只读）。
 */
public final class Graph {

    private final boolean directed;
    private final TreeMap<Integer, TreeMap<Integer, Double>> adj = new TreeMap<>();
    private long edgeCount;

    public Graph(boolean directed) {
        this.directed = directed;
    }

    public boolean directed() {
        return directed;
    }

    public void addNode(int id) {
        adj.computeIfAbsent(id, k -> new TreeMap<>());
    }

    /** 加边（权重默认 1；自环/平行边拒绝；无向边自动双向存储只计一次） */
    public void addEdge(int u, int v, double weight) {
        if (u == v) {
            throw new IllegalArgumentException("自环拒绝: " + u);
        }
        if (hasEdge(u, v) || hasEdge(v, u)) {
            throw new IllegalArgumentException("平行边拒绝: " + u + "->" + v);
        }
        if (weight < 0) {
            throw new IllegalArgumentException("负权边拒绝: " + weight);
        }
        addNode(u);
        addNode(v);
        adj.get(u).put(v, weight);
        if (!directed) {
            adj.get(v).put(u, weight);
        }
        edgeCount++;
    }

    public void addEdge(int u, int v) {
        addEdge(u, v, 1.0d);
    }

    public boolean hasEdge(int u, int v) {
        TreeMap<Integer, Double> neighbors = adj.get(u);
        return neighbors != null && neighbors.containsKey(v);
    }

    public double weight(int u, int v) {
        TreeMap<Integer, Double> neighbors = adj.get(u);
        Double w = neighbors == null ? null : neighbors.get(v);
        if (w == null) {
            throw new IllegalArgumentException("边不存在: " + u + "->" + v);
        }
        return w;
    }

    /** 节点数（order） */
    public int order() {
        return adj.size();
    }

    /** 边数（size，无向图只计一次） */
    public long size() {
        return edgeCount;
    }

    /** 节点升序清单 */
    public List<Integer> nodes() {
        return List.copyOf(adj.keySet());
    }

    /** 邻居升序视图（无向图含双向） */
    public Map<Integer, Double> neighbors(int u) {
        TreeMap<Integer, Double> neighbors = adj.get(u);
        if (neighbors == null) {
            throw new IllegalArgumentException("节点不存在: " + u);
        }
        return java.util.Collections.unmodifiableNavigableMap(neighbors);
    }

    /** BFS 层序（起点可达子图；邻居升序） */
    public List<Integer> bfs(int start) {
        if (!adj.containsKey(start)) {
            return List.of();
        }
        List<Integer> order = new ArrayList<>();
        Set<Integer> visited = new LinkedHashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            order.add(cur);
            for (Integer next : adj.get(cur).keySet()) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return List.copyOf(order);
    }

    /** DFS 先序（递归，邻居升序） */
    public List<Integer> dfs(int start) {
        if (!adj.containsKey(start)) {
            return List.of();
        }
        List<Integer> order = new ArrayList<>();
        Set<Integer> visited = new LinkedHashSet<>();
        dfsRecur(start, visited, order);
        return List.copyOf(order);
    }

    private void dfsRecur(int cur, Set<Integer> visited, List<Integer> order) {
        visited.add(cur);
        order.add(cur);
        for (Integer next : adj.get(cur).keySet()) {
            if (!visited.contains(next)) {
                dfsRecur(next, visited, order);
            }
        }
    }

    /** 导出子图（两端均在 ids 内的边保留；只读不修改原图） */
    public Graph subgraph(Set<Integer> ids) {
        Graph sub = new Graph(directed);
        for (Integer id : ids) {
            if (adj.containsKey(id)) {
                sub.addNode(id);
            }
        }
        for (Map.Entry<Integer, TreeMap<Integer, Double>> entry : adj.entrySet()) {
            if (!ids.contains(entry.getKey())) {
                continue;
            }
            for (Map.Entry<Integer, Double> edge : entry.getValue().entrySet()) {
                if (directed || entry.getKey() < edge.getKey()) {
                    if (ids.contains(edge.getKey()) && !sub.hasEdge(entry.getKey(), edge.getKey())) {
                        sub.addEdge(entry.getKey(), edge.getKey(), edge.getValue());
                    }
                }
            }
        }
        return sub;
    }
}

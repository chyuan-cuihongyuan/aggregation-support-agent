package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 图校验纯函数（工单 0204 AB1）—
 * 合法性：① 节点数 ≥ 1；② 有向无环（Kahn 入度消去）；③ 全部节点从入度 0 节点可达
 * （无孤立子图）；④ 存在出度 0 的终点节点。
 * 拓扑序与入度计算供执行引擎（AB2）复用。
 *
 * @author chyuan
 */
public final class GraphValidator {

    private GraphValidator() {
    }

    /** 校验结果：合法时 errors 为空 */
    public record ValidationResult(boolean valid, List<String> errors) {
    }

    public static ValidationResult validate(WorkflowGraph graph) {
        List<String> errors = new ArrayList<>();
        if (graph == null) {
            errors.add("图为 null");
            return new ValidationResult(false, errors);
        }
        if (graph.nodes().isEmpty()) {
            errors.add("图无节点");
            return new ValidationResult(false, errors);
        }
        Map<String, Integer> indegree = indegreeOf(graph);
        List<String> cycleNodes = kahnTopoOrder(graph, indegree);
        if (cycleNodes.size() < graph.nodes().size()) {
            Set<String> inCycle = new LinkedHashSet<>(indegree.keySet());
            cycleNodes.forEach(inCycle::remove);
            errors.add("检测到环，涉及节点: " + String.join(",", inCycle));
        }
        // 可达性：从全部入度 0 节点出发 BFS，未达节点即孤立
        Set<String> reachable = reachableFromStarts(graph, indegree);
        for (WorkflowGraph.NodeSpec node : graph.nodes()) {
            if (!reachable.contains(node.id())) {
                errors.add("节点不可达（孤立子图）: " + node.id());
            }
        }
        // 终点存在：至少一个出度 0 节点
        boolean hasExit = graph.nodes().stream()
                .anyMatch(n -> graph.edges().stream().noneMatch(e -> e.from().equals(n.id())));
        if (!hasExit) {
            errors.add("无出度 0 的终点节点");
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    /** 入度表（节点 id → 入度） */
    public static Map<String, Integer> indegreeOf(WorkflowGraph graph) {
        Map<String, Integer> indegree = new HashMap<>();
        for (WorkflowGraph.NodeSpec node : graph.nodes()) {
            indegree.putIfAbsent(node.id(), 0);
        }
        for (WorkflowGraph.EdgeSpec edge : graph.edges()) {
            indegree.merge(edge.to(), 1, Integer::sum);
        }
        return indegree;
    }

    /** 出边邻接表（节点 id → 后继列表，保序） */
    public static Map<String, List<String>> adjacencyOf(WorkflowGraph graph) {
        Map<String, List<String>> adj = new HashMap<>();
        for (WorkflowGraph.NodeSpec node : graph.nodes()) {
            adj.putIfAbsent(node.id(), new ArrayList<>());
        }
        for (WorkflowGraph.EdgeSpec edge : graph.edges()) {
            adj.get(edge.from()).add(edge.to());
        }
        return adj;
    }

    /**
     * Kahn 拓扑排序：返回能被消去的节点序列；若图中带环，序列短于节点数
     * （未出现在序列中的即在环上）。入参 indegree 会被复制，不污染调用方。
     */
    public static List<String> kahnTopoOrder(WorkflowGraph graph, Map<String, Integer> indegree) {
        Map<String, Integer> remaining = new HashMap<>(indegree);
        Map<String, List<String>> adj = adjacencyOf(graph);
        Queue<String> ready = new LinkedList<>();
        for (Map.Entry<String, Integer> e : remaining.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<String> order = new ArrayList<>(graph.nodes().size());
        while (!ready.isEmpty()) {
            String current = ready.poll();
            order.add(current);
            for (String next : adj.getOrDefault(current, List.of())) {
                int left = remaining.merge(next, -1, Integer::sum);
                if (left == 0) {
                    ready.add(next);
                }
            }
        }
        return order;
    }

    private static Set<String> reachableFromStarts(WorkflowGraph graph, Map<String, Integer> indegree) {
        Set<String> reachable = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
                reachable.add(e.getKey());
            }
        }
        Map<String, List<String>> adj = adjacencyOf(graph);
        while (!queue.isEmpty()) {
            for (String next : adj.getOrDefault(queue.poll(), List.of())) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reachable;
    }
}

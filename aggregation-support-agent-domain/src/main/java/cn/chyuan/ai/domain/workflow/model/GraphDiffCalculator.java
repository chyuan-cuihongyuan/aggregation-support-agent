package cn.chyuan.ai.domain.workflow.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 图版本 diff 计算器（工单 0275 AI8）—
 * 两个图定义按节点 id / 边 (from,to) 三元组集合比对：
 * 节点 ADDED/REMOVED/CHANGED（config 变化明细）/UNCHANGED，边 ADDED/REMOVED/UNCHANGED。
 * 前后端同构实现（TS 侧见 aggregation web lib/workflow/graph-diff.ts），纯函数零依赖。
 *
 * @author chyuan
 */
public final class GraphDiffCalculator {

    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String CHANGED = "CHANGED";
    public static final String UNCHANGED = "UNCHANGED";

    /** 节点 diff 行 */
    public record NodeDiffRow(String id, String type, String change, Map<String, String> configBefore,
            Map<String, String> configAfter) {
    }

    /** 边 diff 行 */
    public record EdgeDiffRow(String from, String to, String change) {
    }

    /** diff 结果 */
    public record GraphDiff(List<NodeDiffRow> nodes, List<EdgeDiffRow> edges,
            int added, int removed, int changed, int unchanged) {

        public boolean isEmpty() {
            return added == 0 && removed == 0 && changed == 0;
        }
    }

    private GraphDiffCalculator() {
    }

    /** 比较两个图定义（按节点 id 与边三元组） */
    public static GraphDiff diff(WorkflowGraph before, WorkflowGraph after) {
        Map<String, WorkflowGraph.NodeSpec> beforeNodes = new LinkedHashMap<>();
        before.nodes().forEach(n -> beforeNodes.put(n.id(), n));
        Map<String, WorkflowGraph.NodeSpec> afterNodes = new LinkedHashMap<>();
        after.nodes().forEach(n -> afterNodes.put(n.id(), n));

        List<NodeDiffRow> nodeRows = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int changed = 0;
        int unchanged = 0;
        for (Map.Entry<String, WorkflowGraph.NodeSpec> entry : afterNodes.entrySet()) {
            WorkflowGraph.NodeSpec beforeNode = beforeNodes.get(entry.getKey());
            if (beforeNode == null) {
                nodeRows.add(new NodeDiffRow(entry.getKey(), entry.getValue().type(), ADDED, null, entry.getValue().config()));
                added++;
            } else if (!beforeNode.config().equals(entry.getValue().config())
                    || !beforeNode.type().equals(entry.getValue().type())) {
                Map<String, String> configChanges = new LinkedHashMap<>();
                java.util.Set<String> keys = new TreeSet<>(beforeNode.config().keySet());
                keys.addAll(entry.getValue().config().keySet());
                for (String key : keys) {
                    String b = beforeNode.config().get(key);
                    String a = entry.getValue().config().get(key);
                    if (!Objects.equals(b, a)) {
                        configChanges.put(key, b + " -> " + a);
                    }
                }
                if (!beforeNode.type().equals(entry.getValue().type())) {
                    configChanges.put("type", beforeNode.type() + " -> " + entry.getValue().type());
                }
                nodeRows.add(new NodeDiffRow(entry.getKey(), entry.getValue().type(), CHANGED,
                        Map.copyOf(configChanges), null));
                changed++;
            } else {
                nodeRows.add(new NodeDiffRow(entry.getKey(), entry.getValue().type(), UNCHANGED, null, null));
                unchanged++;
            }
        }
        for (WorkflowGraph.NodeSpec node : removedNodes(beforeNodes, afterNodes)) {
            nodeRows.add(new NodeDiffRow(node.id(), node.type(), REMOVED, node.config(), null));
            removed++;
        }

        java.util.Set<String> beforeEdges = new TreeSet<>();
        before.edges().forEach(e -> beforeEdges.add(e.from() + "->" + e.to()));
        java.util.Set<String> afterEdges = new TreeSet<>();
        after.edges().forEach(e -> afterEdges.add(e.from() + "->" + e.to()));

        List<EdgeDiffRow> edgeRows = new ArrayList<>();
        for (String edge : afterEdges) {
            String[] parts = edge.split("->", 2);
            if (beforeEdges.contains(edge)) {
                edgeRows.add(new EdgeDiffRow(parts[0], parts[1], UNCHANGED));
            } else {
                edgeRows.add(new EdgeDiffRow(parts[0], parts[1], ADDED));
            }
        }
        for (String edge : beforeEdges) {
            if (!afterEdges.contains(edge)) {
                String[] parts = edge.split("->", 2);
                edgeRows.add(new EdgeDiffRow(parts[0], parts[1], REMOVED));
            }
        }
        nodeRows.sort((a, b) -> rank(a.change()) - rank(b.change()));
        edgeRows.sort((a, b) -> rank(a.change()) - rank(b.change()));
        return new GraphDiff(List.copyOf(nodeRows), List.copyOf(edgeRows), added, removed, changed, unchanged);
    }

    /** 收集被移除节点（before 有 after 无） */
    private static List<WorkflowGraph.NodeSpec> removedNodes(Map<String, WorkflowGraph.NodeSpec> beforeNodes,
            Map<String, WorkflowGraph.NodeSpec> afterNodes) {
        List<WorkflowGraph.NodeSpec> removedNodes = new ArrayList<>();
        for (Map.Entry<String, WorkflowGraph.NodeSpec> entry : beforeNodes.entrySet()) {
            if (!afterNodes.containsKey(entry.getKey())) {
                removedNodes.add(entry.getValue());
            }
        }
        return removedNodes;
    }

    static int rank(String change) {
        return switch (change) {
            case CHANGED -> 0;
            case ADDED -> 1;
            case REMOVED -> 2;
            default -> 3;
        };
    }
}

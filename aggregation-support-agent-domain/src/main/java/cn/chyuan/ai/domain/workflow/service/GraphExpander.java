package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子图引用展开器（工单 0208 AB5，借鉴 LangGraph subgraphs）—
 * SUBGRAPH 节点按命名空间前缀（{nodeId}.{subNodeId}）展开为实体节点；
 * 入口=子图入度 0 节点承接引用节点入边，出口=子图出度 0 节点承接引用节点出边；
 * 递归深度上限（默认 5）防自引用/互引用打爆。
 *
 * @author chyuan
 */
public class GraphExpander {

    /** 默认最大展开深度 */
    public static final int MAX_DEPTH = 5;

    private final Map<String, WorkflowGraph> subgraphRegistry;
    private final int maxDepth;

    public GraphExpander(Map<String, WorkflowGraph> subgraphRegistry) {
        this(subgraphRegistry, MAX_DEPTH);
    }

    public GraphExpander(Map<String, WorkflowGraph> subgraphRegistry, int maxDepth) {
        this.subgraphRegistry = subgraphRegistry == null ? Map.of() : subgraphRegistry;
        this.maxDepth = maxDepth;
    }

    /** 展开：返回无 SUBGRAPH 节点的等价图（config.subgraph 指定引用名，缺省用节点 id） */
    public WorkflowGraph expand(WorkflowGraph graph) {
        return expand(graph, 1);
    }

    private WorkflowGraph expand(WorkflowGraph graph, int depth) {
        if (depth > maxDepth) {
            throw new IllegalArgumentException("子图展开超过最大深度 " + maxDepth + "（疑似递归引用）");
        }
        boolean hasSubgraph = graph.nodes().stream()
                .anyMatch(n -> WorkflowGraph.TYPE_SUBGRAPH.equals(n.type()));
        if (!hasSubgraph) {
            return graph;
        }
        WorkflowGraph.Builder builder = WorkflowGraph.builder(graph.name());
        Map<String, String> namespace = new HashMap<>();
        List<String[]> extraEdges = new ArrayList<>();
        for (WorkflowGraph.NodeSpec node : graph.nodes()) {
            if (!WorkflowGraph.TYPE_SUBGRAPH.equals(node.type())) {
                builder.node(node.id(), node.type(), node.config());
                continue;
            }
            String refName = node.config().getOrDefault("subgraph", node.id());
            WorkflowGraph sub = subgraphRegistry.get(refName);
            if (sub == null) {
                throw new IllegalArgumentException("子图引用不存在: " + refName);
            }
            WorkflowGraph flat = expand(sub, depth + 1);
            // 子图实体节点按命名空间展开
            for (WorkflowGraph.NodeSpec subNode : flat.nodes()) {
                String qualified = node.id() + "." + subNode.id();
                namespace.put(subNode.id(), qualified);
                builder.node(qualified, subNode.type(), subNode.config());
            }
            for (WorkflowGraph.EdgeSpec edge : flat.edges()) {
                builder.edge(namespace.get(edge.from()), namespace.get(edge.to()));
            }
            // 入口（子图入度 0）承接引用节点入边；出口（子图出度 0）承接引用节点出边
            Map<String, Integer> subIndegree = GraphValidator.indegreeOf(flat);
            for (Map.Entry<String, Integer> e : subIndegree.entrySet()) {
                if (e.getValue() == 0) {
                    for (WorkflowGraph.EdgeSpec incoming : graph.edges()) {
                        if (incoming.to().equals(node.id())) {
                            extraEdges.add(new String[]{incoming.from(), namespace.get(e.getKey())});
                        }
                    }
                }
            }
            for (WorkflowGraph.NodeSpec subNode : flat.nodes()) {
                boolean isExit = flat.edges().stream().noneMatch(e -> e.from().equals(subNode.id()));
                if (isExit) {
                    for (WorkflowGraph.EdgeSpec outgoing : graph.edges()) {
                        if (outgoing.from().equals(node.id())) {
                            extraEdges.add(new String[]{namespace.get(subNode.id()), outgoing.to()});
                        }
                    }
                }
            }
        }
        for (String[] edge : extraEdges) {
            builder.edge(edge[0], edge[1]);
        }
        WorkflowGraph expanded = builder.build();
        cn.chyuan.ai.domain.workflow.service.GraphValidator.ValidationResult validation =
                GraphValidator.validate(expanded);
        if (!validation.valid()) {
            throw new IllegalArgumentException("子图展开后图非法: " + String.join("; ", validation.errors()));
        }
        return expanded;
    }
}

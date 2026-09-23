package cn.chyuan.ai.domain.desiredkernel.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static cn.chyuan.ai.domain.desiredkernel.service.ConfigModel.Block;
import static cn.chyuan.ai.domain.desiredkernel.service.ConfigModel.Config;
import static cn.chyuan.ai.domain.desiredkernel.service.ConfigModel.ListV;
import static cn.chyuan.ai.domain.desiredkernel.service.ConfigModel.Ref;
import static cn.chyuan.ai.domain.desiredkernel.service.ConfigModel.Value;

/**
 * 资源引用图（工单 0672 CB2，terraform 思想）。
 * 资源节点（type.name）与引用边（属性引用+显式 depends_on）/
 * 未知引用拒绝/Kahn 拓扑确定性排序与环成员报告。
 */
public final class ResourceGraph {

    public static final String DEPENDS_ON = "depends_on";

    private final TreeMap<String, Set<String>> edges = new TreeMap<>();
    private final TreeMap<String, Integer> indegree = new TreeMap<>();

    private ResourceGraph() {
    }

    public static ResourceGraph from(Config config) {
        ResourceGraph graph = new ResourceGraph();
        List<Block> resources = config.byType("resource");
        for (Block block : resources) {
            graph.addNode(block.identity());
        }
        for (Block block : resources) {
            String from = block.identity();
            for (String to : collectRefs(block)) {
                if (!graph.edges.containsKey(to)) {
                    throw new IllegalArgumentException("未知资源引用：" + from + " → " + to);
                }
                if (!to.equals(from)) {
                    graph.addEdge(from, to);
                }
            }
        }
        return graph;
    }

    /** from 依赖 to（to 先建） */
    private void addEdge(String from, String to) {
        if (edges.get(from).add(to)) {
            indegree.merge(from, 1, Integer::sum);
        }
    }

    private void addNode(String id) {
        edges.computeIfAbsent(id, k -> new LinkedHashSet<>());
        indegree.putIfAbsent(id, 0);
    }

    private static List<String> collectRefs(Block block) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (Map.Entry<String, Value> e : block.attrs().entrySet()) {
            collectRefValues(e.getValue(), refs);
        }
        return List.copyOf(refs);
    }

    private static void collectRefValues(Value value, Set<String> out) {
        if (value instanceof Ref ref && ref.path().size() >= 2) {
            out.add(ref.path().get(0) + "." + ref.path().get(1));
        } else if (value instanceof ListV list) {
            for (Value item : list.items()) {
                collectRefValues(item, out);
            }
        }
    }

    /** Kahn 变体：节点所有依赖就序即可入序（同名字典序确定性） */
    public List<String> topoOrder() {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        boolean progress = true;
        while (order.size() < edges.size() && progress) {
            progress = false;
            for (String id : edges.keySet()) {
                if (!order.contains(id) && order.containsAll(edges.get(id))) {
                    order.add(id);
                    progress = true;
                }
            }
        }
        if (order.size() < edges.size()) {
            List<String> cycle = new java.util.ArrayList<>();
            for (String id : edges.keySet()) {
                if (!order.contains(id)) {
                    cycle.add(id);
                }
            }
            throw new IllegalArgumentException("资源图存在环：" + String.join(" -> ", cycle));
        }
        return List.copyOf(order);
    }

    public Set<String> resources() {
        return Set.copyOf(edges.keySet());
    }
}

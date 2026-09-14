package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.WeightedEdgeVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 标签传播社区发现（工单 0307 AM2）。
 * graphrag Leiden 思想的确定性简化：初始标签=自身键 → 每轮按节点稳定序取
 * 加权邻居标签多数票（并列取最小标签）→ 无变化收敛或迭代上限出口。
 * 同图重放同划分，domain 纯函数。
 */
public class LabelPropagationCommunityDetector {

    /** 检测（节点键集合 + 加权无向边）；权重 <=0 的边忽略 */
    public CommunityPartitionVO detect(java.util.Collection<String> nodeKeys,
                                       List<WeightedEdgeVO> edges,
                                       int maxIterations) {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("迭代上限必须为正数");
        }
        List<String> nodes = new ArrayList<>();
        for (String key : nodeKeys) {
            if (key != null && !key.trim().isEmpty() && !nodes.contains(key)) {
                nodes.add(key);
            }
        }
        Collections.sort(nodes);

        // 邻接表：节点→(邻居→累计权重)，无向边双向累计
        Map<String, Map<String, Double>> adjacency = new HashMap<>();
        for (String node : nodes) {
            adjacency.put(node, new HashMap<>());
        }
        if (edges != null) {
            for (WeightedEdgeVO edge : edges) {
                if (edge == null || edge.getWeight() <= 0) {
                    continue;
                }
                String a = edge.getSourceKey();
                String b = edge.getTargetKey();
                if (a == null || b == null || a.equals(b)
                        || !adjacency.containsKey(a) || !adjacency.containsKey(b)) {
                    continue;
                }
                adjacency.get(a).merge(b, edge.getWeight(), Double::sum);
                adjacency.get(b).merge(a, edge.getWeight(), Double::sum);
            }
        }

        // 初始标签=自身键；每轮按稳定序多数票更新
        Map<String, String> labels = new HashMap<>();
        for (String node : nodes) {
            labels.put(node, node);
        }
        boolean converged = false;
        int round = 0;
        for (; round < maxIterations; round++) {
            boolean changed = false;
            for (String node : nodes) {
                String best = majorityLabel(adjacency.get(node), labels);
                if (best != null && !best.equals(labels.get(node))) {
                    labels.put(node, best);
                    changed = true;
                }
            }
            if (!changed) {
                converged = true;
                round++;
                break;
            }
        }

        // 社区ID = c_ + 成员最小节点键
        Map<String, List<String>> communities = new TreeMap<>();
        for (String node : nodes) {
            communities.computeIfAbsent("c_" + labels.get(node), k -> new ArrayList<>()).add(node);
        }
        Map<String, String> nodeToCommunity = new LinkedHashMap<>();
        communities.forEach((community, members) ->
                members.forEach(member -> nodeToCommunity.put(member, community)));
        return CommunityPartitionVO.builder()
                .nodeToCommunity(nodeToCommunity)
                .communities(communities)
                .iterations(round)
                .converged(converged)
                .build();
    }

    /** 图索引便捷入口：边取图索引关系边（权重 1.0），节点取索引节点键 */
    public CommunityPartitionVO detect(GraphIndexVO index, int maxIterations) {
        List<WeightedEdgeVO> edges = new ArrayList<>();
        for (GraphEdgeVO edge : index.getEdges()) {
            edges.add(WeightedEdgeVO.builder()
                    .sourceKey(edge.getSourceKey())
                    .targetKey(edge.getTargetKey())
                    .weight(1.0)
                    .build());
        }
        List<String> keys = new ArrayList<>();
        index.getNodes().forEach(node -> keys.add(node.getNodeKey()));
        return detect(keys, edges, maxIterations);
    }

    /** 加权多数票：并列取最小标签；无邻居沿用当前标签（返回 null 表示不变） */
    private String majorityLabel(Map<String, Double> neighbors, Map<String, String> labels) {
        if (neighbors == null || neighbors.isEmpty()) {
            return null;
        }
        Map<String, Double> tally = new TreeMap<>();
        for (Map.Entry<String, Double> entry : neighbors.entrySet()) {
            tally.merge(labels.get(entry.getKey()), entry.getValue(), Double::sum);
        }
        String best = null;
        double bestWeight = -1;
        for (Map.Entry<String, Double> entry : tally.entrySet()) {
            if (entry.getValue() > bestWeight) {
                bestWeight = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }
}

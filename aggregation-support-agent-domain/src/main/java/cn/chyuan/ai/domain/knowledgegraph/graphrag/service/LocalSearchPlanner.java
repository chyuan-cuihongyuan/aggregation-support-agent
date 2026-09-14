package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.LocalSearchContextVO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Local Search 规划器（工单 0309 AM4）。
 * 查询实体锚定（名称归一精确→子串候选消歧）→K 跳邻域扩展（度数上限截断）→
 * 上下文按预算贪心装填（实体优先于关系优先于原文块）。
 * graphrag local search 思想的确定性简化，domain 纯函数。
 */
public class LocalSearchPlanner {

    private final int hops;
    private final int degreeLimit;
    private final int tokenBudget;

    public LocalSearchPlanner(int hops, int degreeLimit, int tokenBudget) {
        if (hops <= 0 || degreeLimit <= 0 || tokenBudget <= 0) {
            throw new IllegalArgumentException("跳数/度数上限/预算必须为正数");
        }
        this.hops = hops;
        this.degreeLimit = degreeLimit;
        this.tokenBudget = tokenBudget;
    }

    public LocalSearchContextVO search(GraphIndexVO index, String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("查询不能为空");
        }
        String key = GraphIndexBuilder.normalize(query);
        Set<String> nodeKeys = new LinkedHashSet<>();
        index.getNodes().forEach(node -> nodeKeys.add(node.getNodeKey()));

        // 锚定：精确键 → 子串候选（多个=歧义，单个=采纳，零=未命中）
        String anchorKey = null;
        List<String> candidates = new ArrayList<>();
        if (nodeKeys.contains(key)) {
            anchorKey = key;
        } else {
            for (String nodeKey : nodeKeys) {
                if (nodeKey.contains(key)) {
                    candidates.add(nodeKey);
                }
            }
            if (candidates.size() == 1) {
                anchorKey = candidates.get(0);
            }
        }
        if (anchorKey == null) {
            return LocalSearchContextVO.builder()
                    .query(query)
                    .anchorKey(null)
                    .anchorStatus(candidates.isEmpty() ? "MISS" : "AMBIGUOUS")
                    .candidateKeys(candidates)
                    .entities(Collections.emptyList())
                    .relations(Collections.emptyList())
                    .textUnits(Collections.emptyList())
                    .estimatedTokens(0)
                    .truncated(false)
                    .build();
        }

        // K 跳 BFS 邻域扩展，逐节点度数截断（邻居按键序稳定取前 degreeLimit）
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (GraphEdgeVO edge : index.getEdges()) {
            adjacency.computeIfAbsent(edge.getSourceKey(), k -> new ArrayList<>()).add(edge.getTargetKey());
            adjacency.computeIfAbsent(edge.getTargetKey(), k -> new ArrayList<>()).add(edge.getSourceKey());
        }
        Set<String> entities = new LinkedHashSet<>();
        entities.add(anchorKey);
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(anchorKey);
        for (int hop = 0; hop < hops && !frontier.isEmpty(); hop++) {
            Deque<String> next = new ArrayDeque<>();
            while (!frontier.isEmpty()) {
                String current = frontier.poll();
                List<String> neighbors = new ArrayList<>(
                        new TreeSet<>(adjacency.getOrDefault(current, Collections.emptyList())));
                for (int i = 0; i < Math.min(degreeLimit, neighbors.size()); i++) {
                    String neighbor = neighbors.get(i);
                    if (entities.add(neighbor)) {
                        next.add(neighbor);
                    }
                }
            }
            frontier = next;
        }

        // 子图关系边
        Set<String> relations = new LinkedHashSet<>();
        for (GraphEdgeVO edge : index.getEdges()) {
            if (entities.contains(edge.getSourceKey()) && entities.contains(edge.getTargetKey())) {
                relations.add(edge.getEdgeKey());
            }
        }

        // 预算贪心装填：锚点实体 → 其余实体 → 关系 → 原文块
        List<String> packedEntities = new ArrayList<>();
        List<String> packedRelations = new ArrayList<>();
        List<String> packedUnits = new ArrayList<>();
        int used = query.length() + anchorKey.length();
        packedEntities.add(anchorKey);
        for (String entity : entities) {
            if (entity.equals(anchorKey)) {
                continue;
            }
            if (used + entity.length() > tokenBudget) {
                return packed(query, anchorKey, packedEntities, packedRelations, packedUnits, used, true);
            }
            packedEntities.add(entity);
            used += entity.length();
        }
        for (String relation : relations) {
            if (used + relation.length() > tokenBudget) {
                return packed(query, anchorKey, packedEntities, packedRelations, packedUnits, used, true);
            }
            packedRelations.add(relation);
            used += relation.length();
        }
        Set<String> relatedUnits = new LinkedHashSet<>();
        index.getEntitySources().forEach((entity, units) -> {
            if (entities.contains(entity)) {
                relatedUnits.addAll(units);
            }
        });
        index.getRelationSources().forEach((relation, units) -> {
            if (relations.contains(relation)) {
                relatedUnits.addAll(units);
            }
        });
        List<String> orderedUnits = new ArrayList<>(relatedUnits);
        Collections.sort(orderedUnits);
        for (String unitId : orderedUnits) {
            if (used + unitId.length() > tokenBudget) {
                return packed(query, anchorKey, packedEntities, packedRelations, packedUnits, used, true);
            }
            packedUnits.add(unitId);
            used += unitId.length();
        }
        return packed(query, anchorKey, packedEntities, packedRelations, packedUnits, used, false);
    }

    private LocalSearchContextVO packed(String query, String anchorKey, List<String> entities,
                                        List<String> relations, List<String> units,
                                        int used, boolean truncated) {
        return LocalSearchContextVO.builder()
                .query(query)
                .anchorKey(anchorKey)
                .anchorStatus("HIT")
                .candidateKeys(Collections.emptyList())
                .entities(new ArrayList<>(entities))
                .relations(new ArrayList<>(relations))
                .textUnits(new ArrayList<>(units))
                .estimatedTokens(used)
                .truncated(truncated)
                .build();
    }
}

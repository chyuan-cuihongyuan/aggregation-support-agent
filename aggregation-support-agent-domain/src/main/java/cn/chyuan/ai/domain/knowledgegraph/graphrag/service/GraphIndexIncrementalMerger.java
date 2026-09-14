package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.EntityAlignReportVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphNodeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.TextUnitVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 增量索引合并器（工单 0311 AM6）。
 * 新文档图索引增量并入既有索引：实体按「归一名 + 类型一致」对齐合并
 * （别名表优先），关系按边键去重，来源块追加；重复导入同文档幂等（哈希不变）。
 * domain 纯函数。
 */
public class GraphIndexIncrementalMerger {

    /** 实体对齐：规范名（归一）或别名表命中（别名表值可为任一既有写法，归一后比对） */
    public EntityAlignReportVO align(GraphIndexVO base, GraphIndexVO incoming, Map<String, String> aliasTable) {
        Map<String, String> alignments = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        int merged = 0;
        int created = 0;
        Set<String> baseKeys = new LinkedHashSet<>();
        base.getNodes().forEach(node -> baseKeys.add(node.getNodeKey()));
        Map<String, String> baseKeyByType = new TreeMap<>();
        base.getNodes().forEach(node -> baseKeyByType.put(node.getNodeKey() + "|" + node.getEntityType(), node.getNodeKey()));

        for (GraphNodeVO node : incoming.getNodes()) {
            String key = node.getNodeKey();
            String aliasKey = resolveAlias(key, aliasTable, baseKeys);
            if (aliasKey != null) {
                alignments.put(key, "ALIAS:" + aliasKey);
                merged++;
                continue;
            }
            if (baseKeys.contains(key)) {
                alignments.put(key, "EXACT");
                merged++;
                continue;
            }
            alignments.put(key, "NONE");
            created++;
        }
        // 类型冲突：同键但既有类型不一致且未经别名指向其他实体
        for (GraphNodeVO node : incoming.getNodes()) {
            String key = node.getNodeKey();
            for (GraphNodeVO baseNode : base.getNodes()) {
                if (baseNode.getNodeKey().equals(key)
                        && !baseNode.getEntityType().equals(node.getEntityType())
                        && resolveAlias(key, aliasTable, baseKeys) == null) {
                    conflicts.add(key);
                }
            }
        }
        return EntityAlignReportVO.builder()
                .alignments(alignments)
                .typeConflicts(conflicts)
                .merged(merged)
                .created(created)
                .build();
    }

    /** 增量合并：实体/边/来源块并入 base；同输入重复合并幂等 */
    public GraphIndexVO merge(GraphIndexVO base, GraphIndexVO incoming, Map<String, String> aliasTable) {
        // 别名重定向表：incoming 键 → base 键
        Map<String, String> redirect = new TreeMap<>();
        if (aliasTable != null) {
            aliasTable.forEach((alias, target) -> {
                String aliasKey = GraphIndexBuilder.normalize(alias);
                String targetKey = GraphIndexBuilder.normalize(target);
                if (baseHasNode(base, targetKey)) {
                    redirect.put(aliasKey, targetKey);
                }
            });
        }

        // 节点合并：base 优先，incoming 新键追加；被重定向键跳过
        Map<String, GraphNodeVO> nodes = new TreeMap<>();
        base.getNodes().forEach(node -> nodes.put(node.getNodeKey(), node));
        for (GraphNodeVO node : incoming.getNodes()) {
            String key = redirect.getOrDefault(node.getNodeKey(), node.getNodeKey());
            if (!nodes.containsKey(key)) {
                nodes.put(key, GraphNodeVO.builder()
                        .nodeKey(key)
                        .entityName(key)
                        .entityType(node.getEntityType())
                        .entityId(node.getEntityId())
                        .build());
            }
        }

        // 文本块：原样追加（文档前缀隔离，天然去重）
        List<TextUnitVO> units = new ArrayList<>(base.getTextUnits());
        for (TextUnitVO unit : incoming.getTextUnits()) {
            if (units.stream().noneMatch(existing -> existing.getUnitId().equals(unit.getUnitId()))) {
                units.add(unit);
            }
        }

        // 实体来源：并集保序去重（含重定向归并）
        Map<String, List<String>> entitySources = new TreeMap<>();
        base.getEntitySources().forEach((key, ids) -> entitySources.put(key, new ArrayList<>(ids)));
        incoming.getEntitySources().forEach((key, ids) -> {
            String target = redirect.getOrDefault(key, key);
            List<String> mergedIds = entitySources.computeIfAbsent(target, k -> new ArrayList<>());
            for (String id : ids) {
                if (!mergedIds.contains(id)) {
                    mergedIds.add(id);
                }
            }
        });

        // 边与关系来源：键去重合并
        Map<String, GraphEdgeVO> edges = new TreeMap<>();
        base.getEdges().forEach(edge -> edges.put(edge.getEdgeKey(), edge));
        Map<String, List<String>> relationSources = new TreeMap<>();
        base.getRelationSources().forEach((key, ids) -> relationSources.put(key, new ArrayList<>(ids)));
        for (GraphEdgeVO edge : incoming.getEdges()) {
            edges.putIfAbsent(edge.getEdgeKey(), edge);
        }
        incoming.getRelationSources().forEach((key, ids) -> {
            List<String> mergedIds = relationSources.computeIfAbsent(key, k -> new ArrayList<>());
            for (String id : ids) {
                if (!mergedIds.contains(id)) {
                    mergedIds.add(id);
                }
            }
        });

        GraphIndexVO merged = GraphIndexVO.builder()
                .documentId(base.getDocumentId())
                .textUnits(units)
                .nodes(new ArrayList<>(nodes.values()))
                .edges(new ArrayList<>(edges.values()))
                .entitySources(entitySources)
                .relationSources(relationSources)
                .build();
        merged.setIndexHash(GraphIndexBuilder.hash(merged));
        return merged;
    }

    private boolean baseHasNode(GraphIndexVO base, String key) {
        return base.getNodes().stream().anyMatch(node -> node.getNodeKey().equals(key));
    }

    /** 别名命中：归一别名在 base 中存在对应目标键时返回目标键 */
    private String resolveAlias(String key, Map<String, String> aliasTable, Set<String> baseKeys) {
        if (aliasTable == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : aliasTable.entrySet()) {
            if (GraphIndexBuilder.normalize(entry.getKey()).equals(key)
                    && baseKeys.contains(GraphIndexBuilder.normalize(entry.getValue()))) {
                return GraphIndexBuilder.normalize(entry.getValue());
            }
        }
        return null;
    }
}

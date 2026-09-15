package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 矛盾检测与失效（工单 0364 AS3，graphiti temporal invalidation）。
 * 同主语同谓语宾语不同 → 冲突裁决（置信度→时间新近→序号）；三元组全等 → 去重不失效；
 * 败者打 invalidAt 失效戳 + CONFLICT 原因；SUPERSEDED 由工厂显式失效承担。
 */
public class ContradictionDetector {

    /** 失效原因常量 */
    public static final String CONFLICT = "CONFLICT";
    public static final String SUPERSEDED = "SUPERSEDED";

    /**
     * 冲突检测报告：resolved=胜者与无冲突边（活性集）；invalidated=失效副本；duplicates=全等重复（不失效）。
     */
    public record Report(List<MemoryEdgeVO> resolved, List<MemoryEdgeVO> invalidated, List<MemoryEdgeVO> duplicates) {
    }

    /**
     * 对活性边做冲突检测与失效（输入须为活性边；不修改入参，失效产出副本）。
     */
    public Report detect(List<MemoryEdgeVO> activeEdges, BiTemporalEdgeFactory factory) {
        Map<String, List<MemoryEdgeVO>> groups = new LinkedHashMap<>();
        if (activeEdges != null) {
            for (MemoryEdgeVO edge : activeEdges) {
                if (!edge.active()) {
                    continue;
                }
                groups.computeIfAbsent(groupKey(edge), k -> new ArrayList<>()).add(edge);
            }
        }
        List<MemoryEdgeVO> resolved = new ArrayList<>();
        List<MemoryEdgeVO> invalidated = new ArrayList<>();
        List<MemoryEdgeVO> duplicates = new ArrayList<>();
        for (List<MemoryEdgeVO> group : groups.values()) {
            // 组内先按归一宾语细分：同宾语为全等组（保留最高置信度，其余记重复），跨宾语才冲突
            Map<String, List<MemoryEdgeVO>> byObject = new LinkedHashMap<>();
            for (MemoryEdgeVO edge : group) {
                byObject.computeIfAbsent(EntityResolver.normalizeKey(edge.getObject()), k -> new ArrayList<>()).add(edge);
            }
            if (byObject.size() == 1) {
                List<MemoryEdgeVO> same = group;
                MemoryEdgeVO keep = same.get(0);
                for (MemoryEdgeVO edge : same) {
                    if (better(edge, keep)) {
                        keep = edge;
                    }
                }
                resolved.add(keep);
                for (MemoryEdgeVO edge : same) {
                    if (edge != keep) {
                        duplicates.add(edge);
                    }
                }
                continue;
            }
            // 跨宾语：全组裁决唯一胜者，其余失效（失效戳=胜者生效时间，冲突自此成立）
            MemoryEdgeVO winner = group.get(0);
            for (MemoryEdgeVO edge : group) {
                if (better(edge, winner)) {
                    winner = edge;
                }
            }
            resolved.add(winner);
            for (MemoryEdgeVO edge : group) {
                if (edge != winner) {
                    invalidated.add(factory.invalidate(edge, winner.getValidFrom(), CONFLICT));
                }
            }
        }
        return new Report(resolved, invalidated, duplicates);
    }

    /** 裁决比较：置信度高者优；同分事实时间新者优；再同序号新者优 */
    private boolean better(MemoryEdgeVO candidate, MemoryEdgeVO incumbent) {
        if (candidate.getConfidence() != incumbent.getConfidence()) {
            return candidate.getConfidence() > incumbent.getConfidence();
        }
        if (candidate.getValidFrom() != incumbent.getValidFrom()) {
            return candidate.getValidFrom() > incumbent.getValidFrom();
        }
        return candidate.getIngestSeq() > incumbent.getIngestSeq();
    }

    private String groupKey(MemoryEdgeVO edge) {
        return EntityResolver.normalizeKey(edge.getSubject()) + "|" + edge.getPredicate().trim();
    }
}

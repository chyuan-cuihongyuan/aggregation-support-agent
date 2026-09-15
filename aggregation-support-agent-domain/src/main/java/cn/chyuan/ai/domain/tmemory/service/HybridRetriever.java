package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索（工单 0367 AS6，graphiti hybrid search + 0163 RRF 先例）。
 * 图通道：种子实体 BFS 邻域扩展（跳数可配）；词通道：查询词覆盖打分；
 * RRF 融合后乘时间衰减（半衰期可配）→ TopK，带通道来源标注。
 */
public class HybridRetriever {

    /** RRF 平滑常数（Cormack 标准 60） */
    static final int RRF_K = 60;

    private final int maxHops;
    private final int topK;
    private final long halfLifeMs;

    public HybridRetriever(int maxHops, int topK, long halfLifeMs) {
        if (maxHops < 0) {
            throw new IllegalArgumentException("跳数不可为负");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("TopK 至少为 1");
        }
        this.maxHops = maxHops;
        this.topK = topK;
        this.halfLifeMs = Math.max(1, halfLifeMs);
    }

    /** 检索结果项：边 + 融合分 + 通道标注 */
    public record Retrieved(MemoryEdgeVO edge, double score, List<String> channels) {
    }

    public List<Retrieved> retrieve(List<MemoryEdgeVO> edges, List<String> queryTokens,
                                    Set<String> seedEntities, long nowMs) {
        if (edges == null || edges.isEmpty()) {
            return List.of();
        }
        Set<String> seeds = new LinkedHashSet<>();
        for (String seed : seedEntities == null ? Set.<String>of() : seedEntities) {
            seeds.add(EntityResolver.normalizeKey(seed));
        }
        // 图通道：BFS 求每条边到种子的最小跳数
        Map<String, Integer> entityHop = bfsEntityHops(edges, seeds);
        Map<String, List<Integer>> rankings = new LinkedHashMap<>();
        List<Integer> graphRank = new ArrayList<>();
        List<MemoryEdgeVO> active = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            MemoryEdgeVO edge = edges.get(i);
            if (!edge.active()) {
                continue;
            }
            active.add(edge);
            Integer hop = minHop(edge, entityHop);
            if (hop != null && hop <= maxHops) {
                graphRank.add(active.size() - 1);
            }
        }
        graphRank.sort((a, b) -> {
            int hopDiff = minHop(active.get(a), entityHop) - minHop(active.get(b), entityHop);
            return hopDiff != 0 ? hopDiff : Long.compare(active.get(b).getIngestSeq(), active.get(a).getIngestSeq());
        });
        rankings.put("graph", graphRank);
        // 词通道：查询词覆盖率
        List<Integer> keywordRank = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            if (coverage(active.get(i), queryTokens) > 0) {
                keywordRank.add(i);
            }
        }
        keywordRank.sort((a, b) -> Double.compare(coverage(active.get(b), queryTokens), coverage(active.get(a), queryTokens)));
        rankings.put("keyword", keywordRank);
        // RRF 融合 + 时间衰减
        Map<Integer, Double> fused = new HashMap<>();
        Map<Integer, Set<String>> channelTags = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : rankings.entrySet()) {
            int rank = 1;
            for (Integer idx : entry.getValue()) {
                fused.merge(idx, 1.0 / (RRF_K + rank), Double::sum);
                channelTags.computeIfAbsent(idx, k -> new LinkedHashSet<>()).add(entry.getKey());
                rank++;
            }
        }
        List<Retrieved> out = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : fused.entrySet()) {
            MemoryEdgeVO edge = active.get(entry.getKey());
            double decay = Math.pow(0.5, (double) Math.max(0, nowMs - edge.getValidFrom()) / halfLifeMs);
            out.add(new Retrieved(edge, round(entry.getValue() * decay),
                    List.copyOf(channelTags.get(entry.getKey()))));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.size() <= topK ? out : out.subList(0, topK);
    }

    /** 种子实体 BFS：实体→最小跳数（边把主语宾语连成无向图） */
    private Map<String, Integer> bfsEntityHops(List<MemoryEdgeVO> edges, Set<String> seeds) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (MemoryEdgeVO edge : edges) {
            String s = EntityResolver.normalizeKey(edge.getSubject());
            String o = EntityResolver.normalizeKey(edge.getObject());
            adjacency.computeIfAbsent(s, k -> new HashSet<>()).add(o);
            adjacency.computeIfAbsent(o, k -> new HashSet<>()).add(s);
        }
        Map<String, Integer> hops = new HashMap<>();
        Set<String> frontier = new LinkedHashSet<>();
        for (String seed : seeds) {
            if (adjacency.containsKey(seed)) {
                hops.put(seed, 0);
                frontier.add(seed);
            }
        }
        int depth = 0;
        while (!frontier.isEmpty() && depth < maxHops) {
            Set<String> next = new LinkedHashSet<>();
            for (String entity : frontier) {
                for (String neighbor : adjacency.getOrDefault(entity, Set.of())) {
                    if (!hops.containsKey(neighbor)) {
                        hops.put(neighbor, depth + 1);
                        next.add(neighbor);
                    }
                }
            }
            frontier = next;
            depth++;
        }
        return hops;
    }

    /** 边到种子的最小跳数（端点 hop 取小；无种子连通返回 null） */
    private Integer minHop(MemoryEdgeVO edge, Map<String, Integer> entityHop) {
        Integer s = entityHop.get(EntityResolver.normalizeKey(edge.getSubject()));
        Integer o = entityHop.get(EntityResolver.normalizeKey(edge.getObject()));
        if (s == null && o == null) {
            return null;
        }
        if (s == null) {
            return o;
        }
        if (o == null) {
            return s;
        }
        return Math.min(s, o);
    }

    /** 查询词覆盖率：命中查询词数 / 查询词数 */
    private double coverage(MemoryEdgeVO edge, List<String> queryTokens) {
        if (queryTokens == null || queryTokens.isEmpty()) {
            return 0.0;
        }
        String text = EntityResolver.normalizeKey(edge.getSubject() + edge.getPredicate() + edge.getObject());
        long hits = queryTokens.stream().filter(token -> text.contains(EntityResolver.normalizeKey(token))).count();
        return (double) hits / queryTokens.size();
    }

    private static double round(double value) {
        return Math.round(value * 100000) / 100000.0;
    }
}

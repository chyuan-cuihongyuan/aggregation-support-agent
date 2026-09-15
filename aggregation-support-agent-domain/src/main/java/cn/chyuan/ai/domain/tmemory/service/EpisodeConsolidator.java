package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.adapter.port.ISummaryPort;
import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 情景批次→情节摘要→语义晋升（工单 0366 AS5，mem0/letta 记忆沉淀思想）。
 * episodic 边按时间窗分组；每窗生成情节摘要（端口失败模板兜底）；
 * 归一三元组跨窗出现次数≥阈值 → 晋升 semantic 边（带晋升来源窗批次）。纯函数，重放幂等。
 */
public class EpisodeConsolidator {

    private final long windowMs;
    private final int promoteThreshold;
    private final ISummaryPort summaryPort;

    public EpisodeConsolidator(long windowMs, int promoteThreshold, ISummaryPort summaryPort) {
        if (windowMs <= 0) {
            throw new IllegalArgumentException("时间窗必须为正: " + windowMs);
        }
        if (promoteThreshold < 1) {
            throw new IllegalArgumentException("晋升阈值至少为 1: " + promoteThreshold);
        }
        this.windowMs = windowMs;
        this.promoteThreshold = promoteThreshold;
        this.summaryPort = summaryPort;
    }

    /**
     * 沉淀结果：每窗一条摘要边（EPISODIC，predicate=episode-summary）+ 晋升的语义边。
     */
    public record Consolidation(List<MemoryEdgeVO> summaries, List<MemoryEdgeVO> promoted) {
    }

    public Consolidation consolidate(List<MemoryEdgeVO> episodicEdges, BiTemporalEdgeFactory factory, long originMs) {
        // 1. 时间窗分组（按 validFrom 落窗）
        Map<Long, List<MemoryEdgeVO>> windows = new LinkedHashMap<>();
        for (MemoryEdgeVO edge : episodicEdges == null ? List.<MemoryEdgeVO>of() : episodicEdges) {
            if (!"EPISODIC".equals(edge.getKind()) || !edge.active()) {
                continue;
            }
            long windowIndex = Math.max(0, (edge.getValidFrom() - originMs) / windowMs);
            windows.computeIfAbsent(windowIndex, k -> new ArrayList<>()).add(edge);
        }
        // 2. 每窗摘要（端口失败模板兜底）
        List<MemoryEdgeVO> summaries = new ArrayList<>();
        Map<String, Set<Long>> factWindows = new LinkedHashMap<>();
        Map<String, MemoryEdgeVO> factFirst = new LinkedHashMap<>();
        List<MemoryEdgeVO> ordered = new ArrayList<>();
        for (Map.Entry<Long, List<MemoryEdgeVO>> entry : windows.entrySet()) {
            long windowIndex = entry.getKey();
            List<MemoryEdgeVO> batch = entry.getValue();
            List<String> facts = batch.stream()
                    .map(e -> e.getSubject() + " " + e.getPredicate() + " " + e.getObject())
                    .toList();
            String summary = renderSummary(facts);
            summaries.add(MemoryEdgeVO.builder()
                    .edgeId("summary-" + windowIndex)
                    .subject("window-" + windowIndex)
                    .predicate("episode-summary")
                    .object(summary)
                    .validFrom(batch.get(0).getValidFrom())
                    .ingestSeq(-windowIndex - 1)
                    .confidence(0.5)
                    .source("tmemory")
                    .kind("EPISODIC")
                    .build());
            // 3. 归一三元组跨窗计数
            for (MemoryEdgeVO edge : batch) {
                String factKey = EntityResolver.normalizeKey(edge.getSubject()) + "|"
                        + edge.getPredicate().trim() + "|" + EntityResolver.normalizeKey(edge.getObject());
                factWindows.computeIfAbsent(factKey, k -> new LinkedHashSet<>()).add(windowIndex);
                factFirst.putIfAbsent(factKey, edge);
            }
            ordered.add(batch.get(0));
        }
        // 4. 跨窗稳定事实晋升 semantic（来源批次=出现窗清单）
        List<MemoryEdgeVO> promoted = new ArrayList<>();
        for (Map.Entry<String, Set<Long>> entry : factWindows.entrySet()) {
            if (entry.getValue().size() < promoteThreshold) {
                continue;
            }
            MemoryEdgeVO first = factFirst.get(entry.getKey());
            List<String> batches = entry.getValue().stream().map(String::valueOf).sorted().toList();
            promoted.add(MemoryEdgeVO.builder()
                    .edgeId("promoted-" + entry.getKey().hashCode())
                    .subject(first.getSubject())
                    .predicate(first.getPredicate())
                    .object(first.getObject())
                    .validFrom(first.getValidFrom())
                    .ingestSeq(Long.MAX_VALUE - promoted.size())
                    .confidence(Math.min(1.0, first.getConfidence() + 0.1))
                    .source(first.getSource())
                    .kind("SEMANTIC")
                    .promotedFrom(batches)
                    .build());
        }
        return new Consolidation(summaries, promoted);
    }

    /** 摘要渲染：端口优先，异常/缺失模板兜底 */
    private String renderSummary(List<String> facts) {
        if (summaryPort != null) {
            try {
                String summary = summaryPort.summarize(facts);
                if (summary != null && !summary.isBlank()) {
                    return summary;
                }
            } catch (Exception ignored) {
                // 兜底模板承接
            }
        }
        return "情节摘要: " + String.join("；", facts);
    }
}

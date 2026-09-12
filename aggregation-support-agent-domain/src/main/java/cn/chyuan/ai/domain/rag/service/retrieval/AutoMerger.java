package cn.chyuan.ai.domain.rag.service.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动合并检索纯函数（工单 0230 AE3，借鉴 LlamaIndex auto-merging）—
 * 同父块的子块命中率 ≥ 阈值时上卷为父块结果（去重，保最优分数），否则保留子块。
 * 输入 = 命中子块列表（chunkId/parentId/score/text），输出 = 合并后列表（父块优先，
 * score 取其子块最高分）。
 *
 * @author chyuan
 */
public final class AutoMerger {

    /** 命中子块 */
    public record ChildHit(String chunkId, String parentId, double score, String text) {
    }

    /** 合并后条目 */
    public record MergedHit(String id, boolean mergedParent, double score, String text) {
    }

    private final double threshold;

    public AutoMerger(double threshold) {
        this.threshold = threshold <= 0 ? 0.6d : threshold;
    }

    public AutoMerger() {
        this(0.6d);
    }

    /** 合并：按 parentId 分组，命中率（组内命中数/父块子块总数）≥ 阈值上卷 */
    public List<MergedHit> merge(List<ChildHit> hits, int childrenPerParent) {
        List<MergedHit> out = new ArrayList<>();
        if (hits == null || hits.isEmpty()) {
            return out;
        }
        int totalPerParent = Math.max(1, childrenPerParent);
        // parentId → 组内命中
        Map<String, List<ChildHit>> groups = new LinkedHashMap<>();
        List<ChildHit> orphans = new ArrayList<>();
        for (ChildHit hit : hits) {
            if (hit.parentId() == null || hit.parentId().isBlank()) {
                orphans.add(hit);
            } else {
                groups.computeIfAbsent(hit.parentId(), k -> new ArrayList<>()).add(hit);
            }
        }
        for (Map.Entry<String, List<ChildHit>> e : groups.entrySet()) {
            List<ChildHit> group = e.getValue();
            double hitRatio = (double) group.size() / totalPerParent;
            if (hitRatio >= threshold) {
                // 上卷：取组内最高分代表
                ChildHit best = group.get(0);
                for (ChildHit hit : group) {
                    if (hit.score() > best.score()) {
                        best = hit;
                    }
                }
                out.add(new MergedHit(e.getKey(), true, best.score(),
                        "[父块] " + best.text()));
            } else {
                group.forEach(hit -> out.add(
                        new MergedHit(hit.chunkId(), false, hit.score(), hit.text())));
            }
        }
        orphans.forEach(hit -> out.add(new MergedHit(hit.chunkId(), false, hit.score(), hit.text())));
        return out;
    }

    public double threshold() {
        return threshold;
    }
}

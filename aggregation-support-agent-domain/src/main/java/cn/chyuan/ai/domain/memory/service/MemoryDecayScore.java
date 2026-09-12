package cn.chyuan.ai.domain.memory.service;

import java.util.List;

/**
 * 记忆时间衰减分数纯函数（工单 0232 AE5，借鉴 Mem0/Zep 时效性）—
 * score = 频次权重 × exp(-λ × 天数)；λ = ln2 / halfLifeDays（半衰期语义）；
 * 单调性：频次越高分越高、越久越低；分数用于召回排序加权（开关默认关）。
 *
 * @author chyuan
 */
public final class MemoryDecayScore {

    private final double lambda;

    /** @param halfLifeDays 半衰期（天），>0 */
    public MemoryDecayScore(double halfLifeDays) {
        double safe = halfLifeDays > 0 ? halfLifeDays : 7.0d;
        this.lambda = Math.log(2) / safe;
    }

    /** 默认半衰期 7 天 */
    public MemoryDecayScore() {
        this(7.0d);
    }

    /**
     * 衰减分：accessCount 为频次权重（每 5 次封顶一份权重，weight = 1 + min(count,5)*0.2）
     *
     * @param lastAccessAt 最后访问时间（epoch 毫秒）
     * @param nowMs        当前时间
     */
    public double score(int accessCount, long lastAccessAt, long nowMs) {
        double weight = 1 + Math.min(Math.max(0, accessCount), 5) * 0.2d;
        double days = Math.max(0, nowMs - lastAccessAt) / 86_400_000.0d;
        return weight * Math.exp(-lambda * days);
    }

    /** 半衰期校验：halfLife 天后分数恰为权重一半 */
    public static double decayFactor(double halfLifeDays, double days) {
        double lambda = Math.log(2) / (halfLifeDays > 0 ? halfLifeDays : 7.0d);
        return Math.exp(-lambda * Math.max(0, days));
    }

    /** 批量按衰减分排序（降序；返回原列表下标序） */
    public List<Integer> rankByDecay(List<Integer> accessCounts, List<Long> lastAccessTimes, long nowMs) {
        List<Integer> indexes = new java.util.ArrayList<>();
        if (accessCounts == null || lastAccessTimes == null) {
            return indexes;
        }
        for (int i = 0; i < accessCounts.size(); i++) {
            indexes.add(i);
        }
        indexes.sort((a, b) -> Double.compare(
                score(lastAccessTimes.size() > b ? accessCounts.get(b) : 0,
                        lastAccessTimes.size() > b ? lastAccessTimes.get(b) : 0, nowMs),
                score(lastAccessTimes.size() > a ? accessCounts.get(a) : 0,
                        lastAccessTimes.size() > a ? lastAccessTimes.get(a) : 0, nowMs)));
        return indexes;
    }
}

package cn.chyuan.ai.domain.rag.service.profile;

import java.util.HashMap;
import java.util.Map;

/**
 * 检索配置画像对比纯函数（工单 0236 AE9）—
 * 两配置组合各指标差值 + 优者标记（命中率优先，同命中比延迟；只观测不路由，
 * AB 实验维持出界——0194 D4）。
 *
 * @author chyuan
 */
public final class ProfileComparator {

    /** 对比结果 */
    public record Comparison(String keyA, String keyB, double hitRateDiff, double latencyDiffMs,
            String betterKey, String basis) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new HashMap<>();
            out.put("keyA", keyA);
            out.put("keyB", keyB);
            out.put("hitRateDiff", hitRateDiff);
            out.put("latencyDiffMs", latencyDiffMs);
            out.put("better", betterKey);
            out.put("basis", basis);
            return out;
        }
    }

    private ProfileComparator() {
    }

    /**
     * 对比：hitRateDiff = A命中 - B命中（正=A优）；latencyDiffMs = A延迟 - B延迟（负=A优）。
     * 优者判定：命中率差 ≥ 0.02 按命中率（basis=hit_rate），否则按延迟（basis=latency），
     * 两维均持平（差 < 0.01 / 5ms）判平（betterKey=""）。
     */
    public static Comparison compare(RetrievalProfileCollector.ProfileStats statsA,
            RetrievalProfileCollector.ProfileStats statsB) {
        double hitDiff = statsA.avgHitRate() - statsB.avgHitRate();
        double latencyDiff = statsA.avgLatencyMs() - statsB.avgLatencyMs();
        String better;
        String basis;
        if (hitDiff >= 0.02d) {
            better = statsA.key();
            basis = "hit_rate";
        } else if (hitDiff <= -0.02d) {
            better = statsB.key();
            basis = "hit_rate";
        } else if (latencyDiff <= -5d) {
            better = statsA.key();
            basis = "latency";
        } else if (latencyDiff >= 5d) {
            better = statsB.key();
            basis = "latency";
        } else {
            better = "";
            basis = "tie";
        }
        return new Comparison(statsA.key(), statsB.key(), hitDiff, latencyDiff, better, basis);
    }
}

package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 记忆冲突仲裁（工单 0368 AS7，mem0 冲突处理）。
 * 默认加权策略：置信度×新鲜度×来源权威；策略接口可插拔；并列按时间新近→序号打破。
 */
public class ConflictArbiter {

    /** 仲裁策略端口 */
    public interface IArbiterStrategy {

        Arbitration arbitrate(List<MemoryEdgeVO> candidates, long nowMs);
    }

    private final IArbiterStrategy strategy;

    /** 默认加权规则策略（置信度×新鲜度×权威，权威表可配） */
    public static final class WeightedRuleStrategy implements IArbiterStrategy {

        private final Map<String, Double> authorityBySource;
        private final long halfLifeMs;

        public WeightedRuleStrategy(Map<String, Double> authorityBySource, long halfLifeMs) {
            this.authorityBySource = authorityBySource == null ? Map.of() : authorityBySource;
            this.halfLifeMs = Math.max(1, halfLifeMs);
        }

        @Override
        public Arbitration arbitrate(List<MemoryEdgeVO> candidates, long nowMs) {
            record Scored(MemoryEdgeVO edge, double score, String reason) {
            }
            List<Scored> scored = new ArrayList<>();
            for (MemoryEdgeVO edge : candidates == null ? List.<MemoryEdgeVO>of() : candidates) {
                double authority = authorityBySource.getOrDefault(edge.getSource(), 0.5);
                double freshness = Math.pow(0.5, (double) Math.max(0, nowMs - edge.getValidFrom()) / halfLifeMs);
                double score = round(edge.getConfidence() * freshness * authority);
                scored.add(new Scored(edge, score,
                        "conf=" + edge.getConfidence() + ",fresh=" + round(freshness)
                                + ",auth=" + authority + ",score=" + score));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                    .thenComparing(s -> -s.edge().getValidFrom())
                    .thenComparing(s -> -s.edge().getIngestSeq()));
            if (scored.isEmpty()) {
                return new Arbitration(null, List.of());
            }
            List<Ranked> ranked = new ArrayList<>();
            for (int i = 1; i < scored.size(); i++) {
                ranked.add(new Ranked(scored.get(i).edge(), scored.get(i).score(), scored.get(i).reason()));
            }
            return new Arbitration(scored.get(0).edge(),
                    ranked);
        }
    }

    /** 裁决结果：胜者 + 败者排序（带裁决依据） */
    public record Arbitration(MemoryEdgeVO winner, List<Ranked> losers) {
    }

    public record Ranked(MemoryEdgeVO edge, double score, String reason) {
    }

    public ConflictArbiter() {
        this(new WeightedRuleStrategy(Map.of(), 86_400_000L));
    }

    public ConflictArbiter(IArbiterStrategy strategy) {
        this.strategy = strategy;
    }

    public Arbitration arbitrate(List<MemoryEdgeVO> candidates, long nowMs) {
        return strategy.arbitrate(candidates, nowMs);
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}

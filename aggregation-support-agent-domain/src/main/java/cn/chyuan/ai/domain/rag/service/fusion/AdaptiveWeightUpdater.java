package cn.chyuan.ai.domain.rag.service.fusion;

import java.util.List;
import java.util.Map;

/**
 * 召回权重自适应更新纯函数（工单 0234 AE7，借鉴 Qdrant hybrid 自适应思想）—
 * 输入各路（向量/关键词）本次贡献命中数 vs 当前权重 → 小步更新：
 * w' = w + learningRate × (贡献占比 - w)，钳制 [minRatio, maxRatio] 并归一（和=1）。
 * 与 RRF 融合（0163）接入：开关 rag.adaptive-weight.enabled 默认关。
 *
 * @author chyuan
 */
public final class AdaptiveWeightUpdater {

    /** 两路权重（和=1） */
    public record Weights(double vectorRatio, double keywordRatio) {
        public Weights {
            if (vectorRatio < 0) {
                vectorRatio = 0;
            }
            if (keywordRatio < 0) {
                keywordRatio = 0;
            }
            double sum = vectorRatio + keywordRatio;
            if (sum <= 0) {
                vectorRatio = 0.5d;
                keywordRatio = 0.5d;
            } else if (Math.abs(sum - 1) > 1e-9) {
                vectorRatio = vectorRatio / sum;
                keywordRatio = keywordRatio / sum;
            }
        }
    }

    private final double learningRate;
    private final double minRatio;
    private final double maxRatio;

    public AdaptiveWeightUpdater(double learningRate, double minRatio, double maxRatio) {
        this.learningRate = learningRate <= 0 ? 0.1d : learningRate;
        this.minRatio = Math.max(0, minRatio);
        this.maxRatio = Math.min(1, Math.max(this.minRatio, maxRatio));
    }

    /** 默认：学习率 0.1、钳制 [0.2, 0.8] */
    public AdaptiveWeightUpdater() {
        this(0.1d, 0.2d, 0.8d);
    }

    /**
     * 更新：contribVector/contribKeyword 为本次两路贡献命中数（RRF 融合后 topK 中各路来源计数）。
     * 全 0（无贡献）不更新原样返回。
     */
    public Weights update(Weights current, int contribVector, int contribKeyword) {
        int total = Math.max(0, contribVector) + Math.max(0, contribKeyword);
        if (total == 0) {
            return current;
        }
        double observedVector = (double) contribVector / total;
        double newVector = clamp(current.vectorRatio()
                + learningRate * (observedVector - current.vectorRatio()));
        return renormalize(newVector, 1 - newVector);
    }

    /** 多轮收敛工具：按每轮贡献批量更新 */
    public Weights updateAll(Weights current, List<int[]> contributions) {
        Weights weights = current;
        if (contributions != null) {
            for (int[] pair : contributions) {
                weights = update(weights, pair[0], pair[1]);
            }
        }
        return weights;
    }

    private double clamp(double value) {
        return Math.min(maxRatio, Math.max(minRatio, value));
    }

    private Weights renormalize(double vector, double keyword) {
        double sum = vector + keyword;
        return new Weights(vector / sum, keyword / sum);
    }

    /** 供观测：权重快照 map */
    public static Map<String, Double> toMap(Weights weights) {
        return Map.of("vector", weights.vectorRatio(), "keyword", weights.keywordRatio());
    }
}

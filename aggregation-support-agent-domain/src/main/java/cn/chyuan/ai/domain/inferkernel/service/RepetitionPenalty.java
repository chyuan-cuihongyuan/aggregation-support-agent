package cn.chyuan.ai.domain.inferkernel.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 重复惩罚（工单 0507 BI4，llama.cpp penalty 思想）。
 * presence（一次出现常数罚）/frequency（按出现次数罚）/punishment（乘性罚）三参数/
 * 最近生成窗口限制（仅窗口内 token 受罚）/应用后概率变化断言支撑。
 */
public class RepetitionPenalty {

    private final double presencePenalty;
    private final double frequencyPenalty;
    private final double punishmentMultiplier;
    private final int windowSize;

    public RepetitionPenalty(double presencePenalty, double frequencyPenalty,
            double punishmentMultiplier, int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("窗口须 > 0");
        }
        this.presencePenalty = presencePenalty;
        this.frequencyPenalty = frequencyPenalty;
        this.punishmentMultiplier = punishmentMultiplier;
        this.windowSize = windowSize;
    }

    /**
     * 对 logits 应用惩罚：仅统计最近 windowSize 个已生成 token。
     * 正 logit：减法惩罚（presence + frequency×次数）与乘性罚（×punishment）；
     * 负 logit：乘性罚放大（÷punishment）。
     */
    public double[] apply(double[] logits, List<Integer> generatedTokens) {
        double[] penalized = logits.clone();
        Set<Integer> seen = new HashSet<>();
        List<Integer> window = generatedTokens.subList(
                Math.max(0, generatedTokens.size() - windowSize), generatedTokens.size());
        for (int token : window) {
            if (!seen.add(token)) {
                continue;
            }
            int occurrences = (int) window.stream().filter(t -> t == token).count();
            double additive = presencePenalty + frequencyPenalty * occurrences;
            penalized[token] = penalized[token] > 0
                    ? (penalized[token] - additive) * punishmentMultiplier
                    : penalized[token] * punishmentMultiplier;
        }
        return penalized;
    }
}

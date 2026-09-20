package cn.chyuan.ai.domain.inferkernel.service;

import java.util.Arrays;

/**
 * logits 基元（工单 0505 BI2）。
 * softmax 归一（max 减除数值稳定）/温度缩放（T→0 收敛 argmax）/
 * logit 偏置（禁用 token 屏蔽为数值安全 -MAX 实现）。
 */
public class LogitsOps {

    /** 数值安全负无穷（避免真实 -Inf 传播 NaN） */
    public static final double NEG_MASK = -1e30;

    /** softmax：max 减除 + exp 归一 */
    public static double[] softmax(double[] logits) {
        double max = Arrays.stream(logits).max().orElseThrow();
        double[] exps = new double[logits.length];
        double sum = 0;
        for (int i = 0; i < logits.length; i++) {
            exps[i] = Math.exp(logits[i] - max);
            sum += exps[i];
        }
        for (int i = 0; i < exps.length; i++) {
            exps[i] /= sum;
        }
        return exps;
    }

    /** 温度缩放：logits / T；T ≤ 0 视为贪心（argmax 置 1 其余 0 的 one-hot） */
    public static double[] applyTemperature(double[] logits, double temperature) {
        if (temperature <= 0) {
            double[] greedy = new double[logits.length];
            int argmax = argmax(logits);
            greedy[argmax] = 1.0;
            return greedy;
        }
        double[] scaled = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            scaled[i] = logits[i] / temperature;
        }
        return scaled;
    }

    /** logit 偏置：对禁用 token 加 NEG_MASK（softmax 后概率≈0），返回新数组 */
    public static double[] applyBias(double[] logits, java.util.Set<Integer> bannedTokens) {
        double[] biased = logits.clone();
        for (int token : bannedTokens) {
            biased[token] += NEG_MASK;
        }
        return biased;
    }

    /** argmax（并列取首个） */
    public static int argmax(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    /** 概率和断言（数值容差） */
    public static double sum(double[] probabilities) {
        return Arrays.stream(probabilities).sum();
    }
}

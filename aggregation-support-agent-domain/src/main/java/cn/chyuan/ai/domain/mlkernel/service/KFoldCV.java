package cn.chyuan.ai.domain.mlkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * K 折交叉验证（工单 0547 BM7，sklearn StratifiedKFold 思想）。
 * 分层 K 折（逐类轮转分配，类比例保持）/折间互斥并集覆盖全量/逐折训练评估/
 * 指标聚合均值与标准差/非法 K 拒绝（K&lt;2 或 K&gt;最少类样本数）。
 */
public final class KFoldCV {

    /** 单折评估回调：返回该折测试集得分 */
    @FunctionalInterface
    public interface FoldEvaluator {
        double evaluate(int[] trainIdx, int[] testIdx);
    }

    /** 聚合指标 */
    public record Scores(double mean, double std, int folds) {
    }

    /** 分层 K 折：返回每折测试下标（升序，折间互斥，并集覆盖全量） */
    public List<int[]> stratifiedFolds(int[] labels, int k) {
        requireK(labels, k);
        Map<Integer, List<Integer>> byClass = new LinkedHashMap<>();
        for (int i = 0; i < labels.length; i++) {
            byClass.computeIfAbsent(labels[i], c -> new ArrayList<>()).add(i);
        }
        List<List<Integer>> folds = new ArrayList<>(k);
        for (int f = 0; f < k; f++) {
            folds.add(new ArrayList<>());
        }
        for (List<Integer> idx : byClass.values()) {
            // 逐类固定种子洗牌后轮转分配，保证分层且确定
            List<Integer> shuffled = new ArrayList<>(idx);
            java.util.Collections.shuffle(shuffled, new Random(20260922L));
            for (int i = 0; i < shuffled.size(); i++) {
                folds.get(i % k).add(shuffled.get(i));
            }
        }
        List<int[]> out = new ArrayList<>(k);
        for (List<Integer> fold : folds) {
            int[] arr = fold.stream().mapToInt(Integer::intValue).toArray();
            java.util.Arrays.sort(arr);
            out.add(arr);
        }
        return out;
    }

    /** 交叉验证：逐折训练评估，聚合均值与标准差 */
    public Scores crossValidate(int[] labels, int k, FoldEvaluator evaluator) {
        List<int[]> folds = stratifiedFolds(labels, k);
        boolean[] seen = new boolean[labels.length];
        double[] scores = new double[folds.size()];
        for (int f = 0; f < folds.size(); f++) {
            int[] test = folds.get(f);
            List<Integer> trainList = new ArrayList<>();
            for (int[] other : folds) {
                if (other == test) {
                    continue;
                }
                for (int idx : other) {
                    trainList.add(idx);
                }
            }
            int[] train = trainList.stream().mapToInt(Integer::intValue).toArray();
            for (int idx : test) {
                if (seen[idx]) {
                    throw new IllegalStateException("折间重叠");
                }
                seen[idx] = true;
            }
            scores[f] = evaluator.evaluate(train, test);
        }
        for (boolean s : seen) {
            if (!s) {
                throw new IllegalStateException("折未覆盖全量样本");
            }
        }
        double mean = java.util.Arrays.stream(scores).average().orElse(0.0d);
        double variance = java.util.Arrays.stream(scores).map(s -> (s - mean) * (s - mean)).sum() / scores.length;
        return new Scores(mean, Math.sqrt(variance), folds.size());
    }

    private void requireK(int[] labels, int k) {
        if (k < 2) {
            throw new IllegalArgumentException("K 须≥2");
        }
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int label : labels) {
            counts.merge(label, 1, Integer::sum);
        }
        int minClass = counts.values().stream().min(Integer::compareTo).orElse(0);
        if (k > minClass) {
            throw new IllegalArgumentException("K 不得超过最少类样本数 " + minClass);
        }
    }
}

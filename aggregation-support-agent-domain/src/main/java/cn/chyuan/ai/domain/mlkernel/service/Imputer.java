package cn.chyuan.ai.domain.mlkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺失插补（工单 0544 BM4，sklearn SimpleImputer 思想）。
 * NaN 缺失标记统计/均值-中位数-众数-常量四策略 fit/transform 插补/
 * 全缺失列拒绝（常量策略除外）。
 */
public final class Imputer {

    public static final double NaN = Double.NaN;

    public enum Strategy {
        MEAN, MEDIAN, MODE, CONSTANT
    }

    /** 插补模型（逐列填充值） */
    public record Model(double[] fillValues) {
        public int columns() {
            return fillValues.length;
        }
    }

    /** 缺失标记计数（逐列） */
    public int[] missingCounts(double[][] x) {
        requireMatrix(x);
        int[] counts = new int[x[0].length];
        for (double[] row : x) {
            for (int c = 0; c < row.length; c++) {
                if (Double.isNaN(row[c])) {
                    counts[c]++;
                }
            }
        }
        return counts;
    }

    /** 拟合：按策略计算逐列填充值 */
    public Model fit(double[][] x, Strategy strategy, double constant) {
        requireMatrix(x);
        int cols = x[0].length;
        double[] fills = new double[cols];
        for (int c = 0; c < cols; c++) {
            List<Double> present = new ArrayList<>();
            for (double[] row : x) {
                if (!Double.isNaN(row[c])) {
                    present.add(row[c]);
                }
            }
            if (present.isEmpty()) {
                if (strategy == Strategy.CONSTANT) {
                    fills[c] = constant;
                    continue;
                }
                throw new IllegalArgumentException("全缺失列拒绝（列 " + c + "）");
            }
            fills[c] = switch (strategy) {
                case MEAN -> present.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
                case MEDIAN -> median(present);
                case MODE -> mode(present);
                case CONSTANT -> constant;
            };
        }
        return new Model(fills);
    }

    /** 插补 NaN */
    public double[][] transform(Model model, double[][] x) {
        requireMatrix(x);
        if (x[0].length != model.columns()) {
            throw new IllegalArgumentException("列数与模型不一致");
        }
        double[][] out = new double[x.length][x[0].length];
        for (int r = 0; r < x.length; r++) {
            for (int c = 0; c < x[0].length; c++) {
                out[r][c] = Double.isNaN(x[r][c]) ? model.fillValues()[c] : x[r][c];
            }
        }
        return out;
    }

    private double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0d;
    }

    private double mode(List<Double> values) {
        Map<Double, Integer> counts = new HashMap<>();
        for (double v : values) {
            counts.merge(v, 1, Integer::sum);
        }
        double best = values.get(0);
        int bestCount = 0;
        for (Map.Entry<Double, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount || (entry.getValue() == bestCount && entry.getKey() < best)) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private void requireMatrix(double[][] x) {
        if (x == null || x.length == 0 || x[0].length == 0) {
            throw new IllegalArgumentException("特征矩阵不得为空");
        }
        for (double[] row : x) {
            if (row.length != x[0].length) {
                throw new IllegalArgumentException("矩阵行宽不一致");
            }
        }
    }
}

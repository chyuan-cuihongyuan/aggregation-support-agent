package cn.chyuan.ai.domain.mlkernel.service;

/**
 * 评估指标（工单 0546 BM6，sklearn metrics 思想）。
 * 混淆矩阵四象限/accuracy-precision-recall-f1（分母零保护取 0）/回归 mse-rmse-mae/
 * 多类宏平均。
 */
public final class Metrics {

    /** 二值计数（TP/FP/FN/TN） */
    public record BinaryCounts(long tp, long fp, long fn, long tn) {
    }

    /** 混淆矩阵：actual×predicted，classes 类（标签 0..classes-1） */
    public int[][] confusion(int[] actual, int[] predicted, int classes) {
        requireSameLength(actual, predicted);
        int[][] matrix = new int[classes][classes];
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] < 0 || actual[i] >= classes || predicted[i] < 0 || predicted[i] >= classes) {
                throw new IllegalArgumentException("标签越界: " + actual[i] + "/" + predicted[i]);
            }
            matrix[actual[i]][predicted[i]]++;
        }
        return matrix;
    }

    /** 二值计数（positiveLabel 为正类标签） */
    public BinaryCounts binaryCounts(int[] actual, int[] predicted, int positiveLabel) {
        long tp = 0;
        long fp = 0;
        long fn = 0;
        long tn = 0;
        for (int i = 0; i < actual.length; i++) {
            boolean a = actual[i] == positiveLabel;
            boolean p = predicted[i] == positiveLabel;
            if (a && p) {
                tp++;
            } else if (!a && p) {
                fp++;
            } else if (a) {
                fn++;
            } else {
                tn++;
            }
        }
        return new BinaryCounts(tp, fp, fn, tn);
    }

    public double accuracy(int[] actual, int[] predicted) {
        requireSameLength(actual, predicted);
        if (actual.length == 0) {
            return 0.0d;
        }
        long hit = 0;
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] == predicted[i]) {
                hit++;
            }
        }
        return hit / (double) actual.length;
    }

    /** 精确率（分母零保护取 0） */
    public double precision(BinaryCounts counts) {
        long denom = counts.tp() + counts.fp();
        return denom == 0 ? 0.0d : counts.tp() / (double) denom;
    }

    /** 召回率（分母零保护取 0） */
    public double recall(BinaryCounts counts) {
        long denom = counts.tp() + counts.fn();
        return denom == 0 ? 0.0d : counts.tp() / (double) denom;
    }

    /** F1（分母零保护取 0） */
    public double f1(BinaryCounts counts) {
        double p = precision(counts);
        double r = recall(counts);
        double denom = p + r;
        return denom == 0 ? 0.0d : 2 * p * r / denom;
    }

    /** 多类宏平均 F1（逐类一比多） */
    public double macroF1(int[] actual, int[] predicted, int classes) {
        double sum = 0.0d;
        for (int label = 0; label < classes; label++) {
            sum += f1(binaryCounts(actual, predicted, label));
        }
        return sum / classes;
    }

    /** 均方误差 */
    public double mse(double[] actual, double[] predicted) {
        requireSameLength(actual, predicted);
        double sum = 0.0d;
        for (int i = 0; i < actual.length; i++) {
            double diff = actual[i] - predicted[i];
            sum += diff * diff;
        }
        return sum / actual.length;
    }

    /** 均方根误差 */
    public double rmse(double[] actual, double[] predicted) {
        return Math.sqrt(mse(actual, predicted));
    }

    /** 平均绝对误差 */
    public double mae(double[] actual, double[] predicted) {
        requireSameLength(actual, predicted);
        double sum = 0.0d;
        for (int i = 0; i < actual.length; i++) {
            sum += Math.abs(actual[i] - predicted[i]);
        }
        return sum / actual.length;
    }

    private void requireSameLength(int[] a, int[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("序列长度不一致");
        }
    }

    private void requireSameLength(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("序列长度不一致");
        }
    }
}

package cn.chyuan.ai.domain.mlkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 特征缩放（工单 0542 BM2，sklearn StandardScaler 思想）。
 * fit 计算列均值与样本方差（ddof=1）标准差/transform 零均值单位方差/
 * 防泄漏（测试集复用训练集参数）/零方差列常数保护（std=1）/逆变换往返等价。
 */
public final class StandardScaler {

    /** 缩放模型（列均值与列标准差） */
    public record Model(double[] mean, double[] std) {
        public int columns() {
            return mean.length;
        }
    }

    public static final double EPS = 1.0E-12d;

    /** 拟合：列均值 + 样本方差开方（单样本列按 0 方差处理） */
    public Model fit(double[][] x) {
        requireMatrix(x);
        int cols = x[0].length;
        double[] mean = new double[cols];
        double[] std = new double[cols];
        for (int c = 0; c < cols; c++) {
            double sum = 0.0d;
            for (double[] row : x) {
                sum += row[c];
            }
            mean[c] = sum / x.length;
            double sq = 0.0d;
            for (double[] row : x) {
                sq += (row[c] - mean[c]) * (row[c] - mean[c]);
            }
            double variance = x.length > 1 ? sq / (x.length - 1) : 0.0d;
            std[c] = variance > EPS ? Math.sqrt(variance) : 1.0d;
        }
        return new Model(mean, std);
    }

    /** 标准化（防泄漏：调用方传入训练集拟合的模型） */
    public double[][] transform(Model model, double[][] x) {
        requireMatrix(x);
        if (x[0].length != model.columns()) {
            throw new IllegalArgumentException("列数与模型不一致");
        }
        double[][] out = new double[x.length][x[0].length];
        for (int r = 0; r < x.length; r++) {
            for (int c = 0; c < x[0].length; c++) {
                out[r][c] = (x[r][c] - model.mean()[c]) / model.std()[c];
            }
        }
        return out;
    }

    /** 逆变换（往返等价口径） */
    public double[][] inverseTransform(Model model, double[][] z) {
        requireMatrix(z);
        double[][] out = new double[z.length][z[0].length];
        for (int r = 0; r < z.length; r++) {
            for (int c = 0; c < z[0].length; c++) {
                out[r][c] = z[r][c] * model.std()[c] + model.mean()[c];
            }
        }
        return out;
    }

    /** 列去重有序视图（编码器共用） */
    static List<Double> sortedDistinct(double[] values) {
        TreeSet<Double> set = new TreeSet<>();
        for (double v : values) {
            set.add(v);
        }
        return new ArrayList<>(set);
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

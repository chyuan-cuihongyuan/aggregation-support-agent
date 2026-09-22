package cn.chyuan.ai.domain.mlkernel.service;

import java.util.List;

/**
 * 管道端口+组合管线（工单 0548 BM8）。
 * MlPipeline（插补→缩放 transformer 链+决策树估计器）fit/transform 拟合预测/
 * 固定种子端到端确定性（同数据同参数同预测，种子端口留扩展）/
 * ml-kernel.enabled 默认关（开启才改变行为）。
 */
public final class MlPipeline {

    /** 拟合产物（插补模型+缩放模型+决策树） */
    public record Fitted(Imputer.Model imputer, StandardScaler.Model scaler, DecisionTree tree) {
    }

    private final Imputer.Strategy imputerStrategy;
    private final boolean scale;
    private final DecisionTree.Criterion criterion;
    private final int maxDepth;
    private final int minSamplesLeaf;
    private final double constant;

    public MlPipeline(Imputer.Strategy imputerStrategy, boolean scale,
            DecisionTree.Criterion criterion, int maxDepth, int minSamplesLeaf, double constant) {
        this.imputerStrategy = imputerStrategy;
        this.scale = scale;
        this.criterion = criterion;
        this.maxDepth = maxDepth;
        this.minSamplesLeaf = minSamplesLeaf;
        this.constant = constant;
    }

    /** 拟合：插补 fit→transform，缩放 fit→transform（防泄漏：两模型仅由训练数据产生），树 fit */
    public Fitted fit(double[][] x, int[] y) {
        Imputer imputer = new Imputer();
        Imputer.Model imputerModel = imputer.fit(x, imputerStrategy, constant);
        double[][] imputed = imputer.transform(imputerModel, x);
        StandardScaler scaler = new StandardScaler();
        StandardScaler.Model scalerModel = scale ? scaler.fit(imputed) : null;
        double[][] features = scale ? scaler.transform(scalerModel, imputed) : imputed;
        DecisionTree tree = new DecisionTree(criterion, maxDepth, minSamplesLeaf).fit(features, y);
        return new Fitted(imputerModel, scalerModel, tree);
    }

    /** 预测：按拟合链变换后走树（测试集复用训练参数——防泄漏） */
    public int[] predict(Fitted fitted, double[][] x) {
        Imputer imputer = new Imputer();
        double[][] imputed = imputer.transform(fitted.imputer(), x);
        double[][] features = fitted.scaler() != null
                ? new StandardScaler().transform(fitted.scaler(), imputed)
                : imputed;
        return fitted.tree().predictAll(features);
    }

    /** 列转置视图（编码器联用口径：类别列抽出→序数回填） */
    public double[][] withOrdinalColumn(double[][] x, int column, List<String> raw,
            CategoryEncoder encoder, CategoryEncoder.Model model) {
        if (raw.size() != x.length) {
            throw new IllegalArgumentException("类别列长度与矩阵行数不一致");
        }
        int[] ordinals = encoder.encodeOrdinalColumn(model, raw, CategoryEncoder.UnknownPolicy.ERROR);
        double[][] out = new double[x.length][x[0].length];
        for (int r = 0; r < x.length; r++) {
            out[r] = x[r].clone();
            out[r][column] = ordinals[r];
        }
        return out;
    }
}

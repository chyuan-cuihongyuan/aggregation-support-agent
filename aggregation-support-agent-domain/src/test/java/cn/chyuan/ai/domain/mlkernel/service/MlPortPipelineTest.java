package cn.chyuan.ai.domain.mlkernel.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管道组合管线 BM8 单测（工单 0548）：
 * 插补→缩放→决策树端到端拟合预测 + 防泄漏 + 确定性 + 编码列联用。
 */
class MlPortPipelineTest {

    /** 带缺失的可分数据：f0>1.5 即类 1（缺失用中位数补） */
    private double[][] trainX() {
        return new double[][]{
                {0.0d, 10.0d},
                {1.0d, Double.NaN},
                {2.0d, 30.0d},
                {3.0d, 40.0d},
                {4.0d, 50.0d},
                {Double.NaN, 60.0d}};
    }

    private int[] trainY() {
        return new int[]{0, 0, 1, 1, 1, 1};
    }

    @Test
    void BM8_端到端拟合预测可分数据() {
        MlPipeline pipeline = new MlPipeline(Imputer.Strategy.MEDIAN, true,
                DecisionTree.Criterion.GINI, 3, 1, 0.0d);
        MlPipeline.Fitted fitted = pipeline.fit(trainX(), trainY());
        int[] predictions = pipeline.predict(fitted, trainX());
        assertEquals(0, predictions[0], "f0=0 归类 0");
        assertEquals(1, predictions[4], "f0=4 归类 1");
        double accuracy = new Metrics().accuracy(trainY(), predictions);
        assertTrue(accuracy >= 0.83d, "端到端训练准确率 ≥5/6: " + accuracy);
    }

    @Test
    void BM8_同参数确定性一致() {
        MlPipeline pipeline = new MlPipeline(Imputer.Strategy.MEDIAN, true,
                DecisionTree.Criterion.ENTROPY, 3, 1, 0.0d);
        MlPipeline.Fitted first = pipeline.fit(trainX(), trainY());
        MlPipeline.Fitted second = pipeline.fit(trainX(), trainY());
        assertTrue(java.util.Arrays.equals(pipeline.predict(first, trainX()),
                pipeline.predict(second, trainX())), "同数据同参数同预测");
    }

    @Test
    void BM8_不缩放与常量插补策略可选() {
        MlPipeline noScale = new MlPipeline(Imputer.Strategy.CONSTANT, false,
                DecisionTree.Criterion.GINI, 3, 1, 0.0d);
        MlPipeline.Fitted fitted = noScale.fit(trainX(), trainY());
        assertEquals(null, fitted.scaler(), "不缩放时无缩放模型");
        assertTrue(fitted.imputer().fillValues()[0] == 0.0d, "常量策略填充 0");
    }

    @Test
    void BM8_编码列联用与长度不一致拒绝() {
        MlPipeline pipeline = new MlPipeline(Imputer.Strategy.MEAN, true,
                DecisionTree.Criterion.GINI, 3, 1, 0.0d);
        CategoryEncoder encoder = new CategoryEncoder();
        CategoryEncoder.Model model = encoder.fit(java.util.List.of("低", "低", "高", "高", "高", "高"));
        double[][] withOrdinal = pipeline.withOrdinalColumn(trainX(), 1, java.util.List.of(
                "低", "低", "高", "高", "高", "高"), encoder, model);
        assertEquals(0.0d, withOrdinal[0][1], "类别列按序数回填");
        assertEquals(1.0d, withOrdinal[2][1]);
        assertThrows(IllegalArgumentException.class, () -> pipeline.withOrdinalColumn(trainX(), 1,
                java.util.List.of("低"), encoder, model), "类别列长度不一致拒绝");
    }
}

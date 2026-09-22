package cn.chyuan.ai.domain.mlkernel.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机器学习管道内核 BM1-BM7 单测（工单 0541-0547）：
 * 划分/缩放/编码/插补/决策树/指标/K 折交叉验证。
 */
class MlKernelTest {

    @Test
    void BM1_划分可复现与分层抽样() {
        DataSplitter splitter = new DataSplitter(42L);
        DataSplitter.Split split = splitter.split(10, 0.3d);
        assertEquals(7, split.train().length);
        assertEquals(3, split.test().length);
        DataSplitter.Split again = new DataSplitter(42L).split(10, 0.3d);
        assertTrue(Arrays.equals(split.test(), again.test()), "同种子划分完全一致");
        assertTrue(new DataSplitter(7L).split(10, 0.3d).test().length == 3);
        int[] labels = {0, 0, 0, 0, 1, 1, 1, 1, 2, 2};
        DataSplitter.Split stratified = splitter.splitStratified(labels, 0.25d);
        assertEquals(3, stratified.test().length, "分层逐类 round(4×0.25)+round(2×0.25)");
        for (int idx : stratified.test()) {
            boolean onlyTest = Arrays.stream(stratified.train()).noneMatch(t -> t == idx);
            assertTrue(onlyTest, "训练测试不重叠");
        }
        assertThrows(IllegalArgumentException.class, () -> splitter.split(10, 0.0d), "非法比例拒绝");
        assertThrows(IllegalArgumentException.class, () -> splitter.split(10, 1.0d), "非法比例拒绝");
    }

    @Test
    void BM2_标准化缩放防泄漏与逆变换() {
        StandardScaler scaler = new StandardScaler();
        double[][] x = {{1.0d}, {3.0d}};
        StandardScaler.Model model = scaler.fit(x);
        assertEquals(2.0d, model.mean()[0], 1.0E-12);
        assertEquals(Math.sqrt(2.0d), model.std()[0], 1.0E-12);
        double[][] z = scaler.transform(model, x);
        assertEquals(-0.7071067811865476d, z[0][0], 1.0E-9);
        double[][] roundtrip = scaler.inverseTransform(model, z);
        assertEquals(1.0d, roundtrip[0][0], 1.0E-12);
        assertEquals(3.0d, roundtrip[1][0], 1.0E-12);
        // 防泄漏：测试集用训练参数
        double[][] test = {{5.0d}};
        assertEquals((5.0d - 2.0d) / Math.sqrt(2.0d), scaler.transform(model, test)[0][0], 1.0E-12);
        // 零方差常数保护
        StandardScaler.Model constModel = scaler.fit(new double[][]{{5.0d}, {5.0d}});
        assertEquals(0.0d, scaler.transform(constModel, new double[][]{{5.0d}})[0][0], 1.0E-12);
        assertThrows(IllegalArgumentException.class, () -> scaler.fit(new double[0][0]), "空矩阵拒绝");
    }

    @Test
    void BM3_类别编码独热可逆与未知策略() {
        CategoryEncoder encoder = new CategoryEncoder();
        CategoryEncoder.Model model = encoder.fit(List.of("b", "a", "c", "a"));
        assertEquals(List.of("a", "b", "c"), model.categories(), "字典序稳定");
        assertTrue(Arrays.equals(new double[]{1, 0, 0}, encoder.oneHot(model, "a", CategoryEncoder.UnknownPolicy.ERROR)));
        assertEquals("b", encoder.decodeOneHot(model, new double[]{0, 1, 0}), "独热可逆");
        assertEquals(1, encoder.ordinal(model, "b", CategoryEncoder.UnknownPolicy.ERROR));
        assertTrue(Arrays.equals(new double[]{0, 0, 0}, encoder.oneHot(model, "未知",
                CategoryEncoder.UnknownPolicy.ZERO)), "未知全零策略");
        assertThrows(IllegalArgumentException.class, () -> encoder.oneHot(model, "未知",
                CategoryEncoder.UnknownPolicy.ERROR), "未知报错策略");
        assertThrows(IllegalArgumentException.class, () -> encoder.decodeOneHot(model, new double[]{1, 1, 0}),
                "非独热拒绝");
        int[] ordinals = encoder.encodeOrdinalColumn(model, List.of("a", "c"), CategoryEncoder.UnknownPolicy.ERROR);
        assertTrue(Arrays.equals(new int[]{0, 2}, ordinals));
    }

    @Test
    void BM4_缺失插补四策略() {
        Imputer imputer = new Imputer();
        double[][] x = {{1.0d, Double.NaN}, {Double.NaN, 2.0d}, {3.0d, 4.0d}};
        assertTrue(Arrays.equals(new int[]{1, 1}, imputer.missingCounts(x)));
        Imputer.Model mean = imputer.fit(x, Imputer.Strategy.MEAN, 0);
        assertEquals(2.0d, mean.fillValues()[0], 1.0E-12);
        double[][] filled = imputer.transform(mean, x);
        assertEquals(2.0d, filled[1][0], 1.0E-12);
        Imputer.Model constant = imputer.fit(x, Imputer.Strategy.CONSTANT, -7.0d);
        assertEquals(-7.0d, constant.fillValues()[1]);
        Imputer.Model median = imputer.fit(new double[][]{{1.0d}, {2.0d}, {3.0d}, {4.0d}, {Double.NaN}},
                Imputer.Strategy.MEDIAN, 0);
        assertEquals(2.5d, median.fillValues()[0], 1.0E-12, "偶数样本取中间均值");
        Imputer.Model mode = imputer.fit(new double[][]{{1.0d}, {1.0d}, {2.0d}}, Imputer.Strategy.MODE, 0);
        assertEquals(1.0d, mode.fillValues()[0], "众数取最多频次（平局取小）");
        assertThrows(IllegalArgumentException.class, () -> imputer.fit(
                new double[][]{{Double.NaN}}, Imputer.Strategy.MEAN, 0), "全缺失列拒绝");
    }

    @Test
    void BM5_决策树分裂剪枝与特征重要性() {
        double[][] x = {{0.0d}, {1.0d}, {2.0d}, {3.0d}};
        int[] y = {0, 0, 1, 1};
        DecisionTree gini = new DecisionTree(DecisionTree.Criterion.GINI, 3, 1).fit(x, y);
        assertTrue(Arrays.equals(new int[]{0, 0, 1, 1}, gini.predictAll(x)), "完全可分训练集零错分");
        assertEquals(0, gini.predict(new double[]{0.5d}));
        assertEquals(1, gini.predict(new double[]{2.5d}));
        double[] importances = gini.featureImportances();
        assertEquals(1, importances.length);
        assertEquals(1.0d, importances[0], 1.0E-12, "单特征重要性归一为 1");
        DecisionTree entropy = new DecisionTree(DecisionTree.Criterion.ENTROPY, 3, 1).fit(x, y);
        assertEquals(0, entropy.predict(new double[]{0.1d}));
        // 剪枝：单类数据叶化
        DecisionTree single = new DecisionTree(DecisionTree.Criterion.GINI, 5, 1)
                .fit(new double[][]{{1.0d}, {2.0d}}, new int[]{1, 1});
        assertEquals(1, single.predict(new double[]{9.0d}));
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionTree(DecisionTree.Criterion.GINI, 0, 1), "深度约束拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionTree(DecisionTree.Criterion.GINI, 3, 0), "叶子样本约束拒绝");
    }

    @Test
    void BM6_混淆矩阵指标族() {
        Metrics metrics = new Metrics();
        int[] actual = {1, 1, 0, 0};
        int[] predicted = {1, 0, 0, 0};
        assertEquals(0.75d, metrics.accuracy(actual, predicted), 1.0E-12);
        Metrics.BinaryCounts counts = metrics.binaryCounts(actual, predicted, 1);
        assertEquals(1, counts.tp());
        assertEquals(0, counts.fp());
        assertEquals(1, counts.fn());
        assertEquals(2, counts.tn());
        assertEquals(1.0d, metrics.precision(counts), 1.0E-12);
        assertEquals(0.5d, metrics.recall(counts), 1.0E-12);
        assertEquals(2.0d / 3.0d, metrics.f1(counts), 1.0E-12);
        assertEquals(0.7333333333333333d, metrics.macroF1(actual, predicted, 2), 1.0E-9,
                "宏平均（类0 F1=0.8 类1 F1=2/3）");
        double[] reg = {1.0d, 2.0d, 3.0d};
        double[] pred = {1.0d, 2.0d, 5.0d};
        assertEquals(4.0d / 3.0d, metrics.mse(reg, pred), 1.0E-12);
        assertEquals(1.1547005383792515d, metrics.rmse(reg, pred), 1.0E-12);
        assertEquals(2.0d / 3.0d, metrics.mae(reg, pred), 1.0E-12);
        assertThrows(IllegalArgumentException.class, () -> metrics.accuracy(new int[]{0}, new int[]{}),
                "长度不一致拒绝");
        // 分母零保护
        assertEquals(0.0d, metrics.precision(metrics.binaryCounts(new int[]{0}, new int[]{0}, 1)));
    }

    @Test
    void BM7_K折分层与指标聚合() {
        KFoldCV cv = new KFoldCV();
        int[] labels = {0, 0, 1, 1, 0, 1};
        List<int[]> folds = cv.stratifiedFolds(labels, 2);
        assertEquals(2, folds.size());
        boolean[] seen = new boolean[labels.length];
        int total = 0;
        for (int[] fold : folds) {
            total += fold.length;
            for (int idx : fold) {
                assertTrue(!seen[idx], "折间互斥");
                seen[idx] = true;
            }
        }
        assertEquals(labels.length, total, "并集覆盖全量");
        KFoldCV.Scores scores = cv.crossValidate(labels, 2,
                (train, test) -> test.length / 3.0d);
        assertEquals(1.0d, scores.mean(), 1.0E-12, "折均分（折大小 4/2，均分 1.0）");
        assertEquals(0.33333333333333326d, scores.std(), 1.0E-9, "折大小不均产生方差");
        assertEquals(2, scores.folds());
        int[] even = {0, 0, 1, 1};
        KFoldCV.Scores evenScores = cv.crossValidate(even, 2, (train, test) -> test.length / 2.0d);
        assertEquals(0.0d, evenScores.std(), 1.0E-12, "等折恒定得分为零方差");
        assertThrows(IllegalArgumentException.class, () -> cv.stratifiedFolds(labels, 1), "K<2 拒绝");
        assertThrows(IllegalArgumentException.class, () -> cv.stratifiedFolds(labels, 4), "K>最少类样本数拒绝");
    }
}

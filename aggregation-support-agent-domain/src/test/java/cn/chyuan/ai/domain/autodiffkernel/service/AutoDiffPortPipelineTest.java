package cn.chyuan.ai.domain.autodiffkernel.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AutoDiffPort 组合管线测试（工单 0662 BZ8，pytorch 思想）。
 * MLP 拟合 XOR 端到端收敛/特征矩阵线性回归拟合（mlkernel 只读联动形态）/
 * 非法参数拒绝。
 */
class AutoDiffPortPipelineTest {

    @Test
    void portTrainXorConverges() {
        AutoDiffPort port = new AutoDiffPort.InMemoryAutoDiff();
        AutoDiffPort.Outcome outcome = port.trainXor(4, 3000, 0.1, 42L);
        assertEquals(3000, outcome.epochs());
        assertEquals(4, outcome.params(), "隐层 w/b + 输出层 w/b 四个参数张量");
        assertTrue(outcome.finalLoss() < 0.05,
                "XOR 应收敛：最终损失 " + outcome.finalLoss());
        assertTrue(outcome.lossCurve()[0] > outcome.finalLoss(),
                "损失曲线整体下降");
    }

    @Test
    void portFitLinearFeatureMatrix() {
        AutoDiffPort port = new AutoDiffPort.InMemoryAutoDiff();
        double[][] features = {{1}, {2}, {3}, {4}, {5}};
        double[] labels = {5, 7, 9, 11, 13};
        AutoDiffPort.Outcome outcome = port.fitLinear(features, labels, 500, 0.3, 7L);
        assertEquals(2, outcome.params(), "w+b");
        assertTrue(outcome.finalLoss() < 1e-4,
                "y=2x+3 拟合应近零损失：最终 " + outcome.finalLoss());
        assertEquals(500, outcome.lossCurve().length);
    }

    @Test
    void portRejectsIllegal() {
        AutoDiffPort port = new AutoDiffPort.InMemoryAutoDiff();
        assertThrows(IllegalArgumentException.class, () -> port.trainXor(0, 100, 0.1, 1L));
        assertThrows(IllegalArgumentException.class, () -> port.trainXor(4, 0, 0.1, 1L));
        assertThrows(IllegalArgumentException.class, () -> port.fitLinear(null, new double[1], 10, 0.1, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> port.fitLinear(new double[][]{{1}, {2}}, new double[1], 10, 0.1, 1L), "行数不一致");
        assertThrows(IllegalArgumentException.class,
                () -> port.fitLinear(new double[][]{{1, 2}}, new double[1], 10, 0.1, 1L), "须为 n×1");
    }
}

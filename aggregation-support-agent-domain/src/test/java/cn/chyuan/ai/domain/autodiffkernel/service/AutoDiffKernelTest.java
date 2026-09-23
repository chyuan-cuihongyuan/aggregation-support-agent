package cn.chyuan.ai.domain.autodiffkernel.service;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动微分内核域单测（工单 0655-0661 BZ1-BZ7，pytorch 思想）。
 * 张量广播与形状拒绝/前向图去重与环拒绝/链式法则与广播梯度规约/
 * matmul 反传/经典层与 Softmax 稳定性/SGD·Adam 更新/有限差分对照/
 * 训练循环损失下降。
 */
class AutoDiffKernelTest {

    @Test
    void tensorBroadcastElementwise() {
        Tensor a = Tensor.of(new double[]{1, 2, 3, 4}, 2, 2);
        Tensor row = Tensor.of(new double[]{10, 20}, 1, 2);
        Tensor col = Tensor.of(new double[]{100, 200}, 2, 1);
        assertArrayEquals(new double[]{11, 22, 13, 24}, a.add(row).data(), 1e-12, "行广播");
        assertArrayEquals(new double[]{101, 102, 203, 204}, a.add(col).data(), 1e-12, "列广播");
        assertArrayEquals(new double[]{10, 40, 30, 80}, a.mul(row).data(), 1e-12);
        Tensor x = Tensor.of(new double[]{1, 2, 3, 4, 5, 6}, 2, 3);
        Tensor y = Tensor.of(new double[]{1, 2, 3, 4, 5, 6}, 3, 2);
        assertThrows(IllegalArgumentException.class, () -> x.add(y), "2x3 与 3x2 不兼容");
    }

    @Test
    void tensorFactoriesAndIndexing() {
        Tensor t = Tensor.of(new double[]{1, 2, 3, 4, 5, 6}, 2, 3);
        assertEquals(6, t.get(1, 2));
        assertEquals(2, t.shape().length);
        assertArrayEquals(new double[]{1, 2, 3, 4, 5, 6}, t.data(), 1e-12);
        Tensor zeros = Tensor.fill(7, 2, 2);
        assertEquals(7, zeros.get(0, 1));
        assertThrows(IllegalArgumentException.class, () -> Tensor.of(new double[3], 2, 2), "长度与形状不匹配");
        assertThrows(IllegalArgumentException.class, () -> Tensor.of(new double[2], 0, 2));
        assertThrows(IllegalArgumentException.class, () -> t.get(2, 0), "索引越界");
    }

    @Test
    void forwardGraphBuildsDagWithTopoDedup() {
        Tensor a = Tensor.parameter(new double[]{2}, 1);
        Tensor b = Tensor.parameter(new double[]{3}, 1);
        Tensor mul = a.mul(b);
        Tensor y = mul.add(mul).mul(mul);
        assertEquals(5, Tensor.nodeCount(y), "a/b/mul/add/mul2 五节点（mul 被引用三次仅计一次）");
        Tensor c = Tensor.parameter(new double[]{1}, 1);
        assertEquals(7, Tensor.nodeCount(a.mul(b).add(a.mul(c)).mul(c)), "不同算子各自成节点");
    }

    @Test
    void cycleRejectedInBackward() {
        Tensor x = Tensor.parameter(new double[]{1}, 1);
        Tensor y = x.add(x);
        x.linkParent(y);
        assertThrows(IllegalArgumentException.class, y::backward, "环路引用应被拒绝");
    }

    @Test
    void backwardChainRuleAnalytic() {
        Tensor a = Tensor.parameter(new double[]{2}, 1);
        Tensor b = Tensor.parameter(new double[]{3}, 1);
        Tensor c = Tensor.parameter(new double[]{4}, 1);
        a.mul(b).add(c).backward();
        assertArrayEquals(new double[]{3}, a.grad(), 1e-12);
        assertArrayEquals(new double[]{2}, b.grad(), 1e-12);
        assertArrayEquals(new double[]{1}, c.grad(), 1e-12);
    }

    @Test
    void backwardBroadcastUnbroadcast() {
        Tensor a = Tensor.parameter(new double[]{1, 2}, 2, 1);
        Tensor b = Tensor.parameter(new double[]{1, 2, 3}, 1, 3);
        a.mul(b).sum().backward();
        assertArrayEquals(new double[]{6, 6}, a.grad(), 1e-12, "行规约 = Σ_j b[0,j]（广播维求和）");
        assertArrayEquals(new double[]{3, 3, 3}, b.grad(), 1e-12, "列规约 = Σ_i a[i,0]");
    }

    @Test
    void matmulForwardAndBackward() {
        Tensor a = Tensor.parameter(new double[]{1, 2, 3, 4}, 2, 2);
        Tensor b = Tensor.parameter(new double[]{5, 6, 7, 8}, 2, 2);
        Tensor c = a.matmul(b);
        assertArrayEquals(new double[]{19, 22, 43, 50}, c.data(), 1e-12);
        c.sum().backward();
        assertArrayEquals(new double[]{11, 15, 11, 15}, a.grad(), 1e-12, "dA[i,k]=Σ_j B[k,j]");
        assertArrayEquals(new double[]{4, 4, 6, 6}, b.grad(), 1e-12, "dB[k,j]=Σ_i A[i,k]");
        assertThrows(IllegalArgumentException.class, () -> a.matmul(Tensor.fill(1, 3, 3)), "内维不匹配");
    }

    @Test
    void layersForwardValues() {
        Tensor x = Tensor.of(new double[]{-1, 0, 2}, 1, 3);
        assertArrayEquals(new double[]{0, 0, 2}, Layers.relu(x).data(), 1e-12);
        assertArrayEquals(new double[]{Math.tanh(-1), 0, Math.tanh(2)}, Layers.tanh(x).data(), 1e-12);
    }

    @Test
    void layersSigmoidSoftmaxMseValues() {
        Tensor s = Layers.softmax(Tensor.of(new double[]{1000, 1000}, 1, 2));
        assertArrayEquals(new double[]{0.5, 0.5}, s.data(), 1e-12, "大值稳定（max 减除）");
        Tensor sm = Layers.softmax(Tensor.of(new double[]{1, 2, 3}, 1, 3));
        assertEquals(1.0, sm.data()[0] + sm.data()[1] + sm.data()[2], 1e-12, "行归一");

        Tensor sig = Layers.sigmoid(Tensor.fill(0, 1));
        assertEquals(0.5, sig.get(0), 1e-12);

        Tensor pred = Tensor.parameter(new double[]{1, 2}, 2, 1);
        Tensor target = Tensor.of(new double[]{3, 6}, 2, 1);
        Tensor loss = Layers.mse(pred, target);
        assertEquals(10.0, loss.get(0), 1e-12, "(4+16)/2");
        loss.backward();
        assertArrayEquals(new double[]{-2, -4}, pred.grad(), 1e-12, "dL/dp = 2(p-t)/n");
    }

    @Test
    void optimizersSGDandAdamDriveQuadraticToMinimum() {
        Tensor w = Tensor.parameter(new double[]{0}, 1);
        Optimizer sgd = Optimizer.sgd(List.of(w), 0.1, 0.9);
        for (int i = 0; i < 50; i++) {
            sgd.zeroGrad();
            w.sub(Tensor.fill(3, 1)).mul(w.sub(Tensor.fill(3, 1))).sum().backward();
            sgd.step();
        }
        assertTrue(Math.abs(w.get(0) - 3) < 0.5, "SGD 动量向极小点收敛，当前 " + w.get(0));

        Tensor v = Tensor.parameter(new double[]{0}, 1);
        Optimizer adam = Optimizer.adam(List.of(v), 0.5, 0.9, 0.999, 1e-8);
        for (int i = 0; i < 100; i++) {
            adam.zeroGrad();
            Tensor d = v.sub(Tensor.fill(3, 1));
            d.mul(d).sum().backward();
            adam.step();
        }
        assertTrue(Math.abs(v.get(0) - 3) < 0.2, "Adam 收敛到极小点附近，当前 " + v.get(0));
        assertThrows(IllegalArgumentException.class, () -> Optimizer.sgd(List.of(w), 0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> Optimizer.adam(List.of(w), 0.1, 1.5, 0.999, 1e-8));
    }

    @Test
    void finiteDiffMatchesAnalytic() {
        Tensor w = Tensor.parameter(new double[]{0.5, -0.7, 1.2}, 3, 1);
        Tensor x = Tensor.of(new double[]{1, 2, 3}, 3, 1);
        Tensor y = Tensor.of(new double[]{1, 0, 2}, 3, 1);
        double err = Gradients.maxRelError(w, () -> Layers.mse(x.mul(w), y), 1e-6);
        assertTrue(err < 1e-4, "有限差分与解析梯度最大相对误差应极小，实际 " + err);
    }

    @Test
    void requireFiniteRejectsNaNInf() {
        assertThrows(IllegalArgumentException.class,
                () -> Gradients.requireFinite(Tensor.of(new double[]{Double.NaN}, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> Gradients.requireFinite(Tensor.of(new double[]{Double.POSITIVE_INFINITY}, 1)));
        Gradients.requireFinite(Tensor.of(new double[]{1, -2.5}, 2));
        assertThrows(IllegalArgumentException.class, () -> Tensor.fill(0, 1).log(), "log 非正域拒绝");
    }

    @Test
    void trainingLoopLinearRegressionLossDecreases() {
        Random rnd = new Random(42);
        Layers.Linear model = new Layers.Linear(1, 1, rnd);
        double[] xs = {1, 2, 3, 4, 5};
        double[] ys = {3, 5, 7, 9, 11};
        Optimizer adam = Optimizer.adam(model.params(), 0.3, 0.9, 0.999, 1e-8);
        var curve = Trainer.fit(() -> Layers.mse(model.forward(Tensor.of(xs, 5, 1)), Tensor.of(ys, 5, 1)),
                model.params(), adam, 300);
        assertTrue(curve.get(curve.size() - 1).loss() < curve.get(0).loss() / 100,
                "损失应显著下降：首 " + curve.get(0).loss() + " 末 " + curve.get(curve.size() - 1).loss());
        assertEquals(300, curve.size());

        int[][] batches = Trainer.batches(7, 3, new Random(1));
        assertEquals(3, batches.length);
        assertEquals(7, batches[0].length + batches[1].length + batches[2].length);
        boolean[] seen = new boolean[7];
        for (int[] b : batches) {
            for (int i : b) {
                assertFalse(seen[i], "批切分互斥全覆盖");
                seen[i] = true;
            }
        }
        int[][] again = Trainer.batches(7, 3, new Random(1));
        assertArrayEquals(batches[0], again[0], "同种子可复现");
        assertThrows(IllegalArgumentException.class, () -> Trainer.batches(0, 3, rnd));
    }
}

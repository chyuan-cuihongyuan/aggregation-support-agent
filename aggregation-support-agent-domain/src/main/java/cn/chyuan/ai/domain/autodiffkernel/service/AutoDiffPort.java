package cn.chyuan.ai.domain.autodiffkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 自动微分端口+组合管线（工单 0662 BZ8）。
 * trainXor：MLP（线性+ReLU 隐层+Sigmoid 输出+MSE+Adam）端到端收敛；
 * fit：特征矩阵→线性回归拟合（与 mlkernel 只读联动形态——特征矩阵入参
 * 形状对齐 mlkernel 特征口径，泛型入参不 import mlkernel，不改其任何类）/
 * autodiff-kernel.enabled 默认关（开启才改变行为）。
 */
public interface AutoDiffPort {

    /** 训练产出：损失曲线/最终损失/轮数/参数量 */
    record Outcome(double[] lossCurve, double finalLoss, int epochs, int params) {
    }

    /** MLP 拟合 XOR（2→hidden ReLU→1 Sigmoid，MSE+Adam） */
    Outcome trainXor(int hidden, int epochs, double lr, long seed);

    /** 特征矩阵线性回归拟合（1→1 线性，mlkernel 特征矩阵只读联动形态） */
    Outcome fitLinear(double[][] features, double[] labels, int epochs, double lr, long seed);

    class InMemoryAutoDiff implements AutoDiffPort {

        @Override
        public Outcome trainXor(int hidden, int epochs, double lr, long seed) {
            if (hidden <= 0 || epochs <= 0 || lr <= 0) {
                throw new IllegalArgumentException("XOR 训练参数非法");
            }
            Random random = new Random(seed);
            Layers.Linear h = new Layers.Linear(2, hidden, random);
            Layers.Linear o = new Layers.Linear(hidden, 1, random);
            List<Tensor> params = Layers.concat(h.params(), o.params());
            double[] xs = {0, 0, 0, 1, 1, 0, 1, 1};
            double[] ys = {0, 1, 1, 0};
            double[] curve = new double[epochs];
            Optimizer adam = Optimizer.adam(params, lr, 0.9, 0.999, 1e-8);
            for (int e = 0; e < epochs; e++) {
                adam.zeroGrad();
                Tensor x = Tensor.of(xs, 4, 2);
                Tensor t = Tensor.of(ys, 4, 1);
                Tensor pred = Layers.sigmoid(o.forward(Layers.relu(h.forward(x))));
                Tensor loss = Layers.mse(pred, t);
                loss.backward();
                adam.step();
                curve[e] = loss.data[0];
            }
            return new Outcome(curve, curve[epochs - 1], epochs, params.size());
        }

        @Override
        public Outcome fitLinear(double[][] features, double[] labels, int epochs, double lr, long seed) {
            if (features == null || labels == null || features.length != labels.length
                    || features.length == 0 || epochs <= 0 || lr <= 0) {
                throw new IllegalArgumentException("特征矩阵与标签非法");
            }
            int n = features.length;
            double[] xs = new double[n];
            for (int i = 0; i < n; i++) {
                if (features[i] == null || features[i].length != 1) {
                    throw new IllegalArgumentException("特征矩阵须为 n×1");
                }
                xs[i] = features[i][0];
            }
            Random random = new Random(seed);
            Layers.Linear model = new Layers.Linear(1, 1, random);
            double[] curve = new double[epochs];
            Optimizer adam = Optimizer.adam(model.params(), lr, 0.9, 0.999, 1e-8);
            for (int e = 0; e < epochs; e++) {
                adam.zeroGrad();
                Tensor x = Tensor.of(xs, n, 1);
                Tensor t = Tensor.of(labels.clone(), n, 1);
                Tensor loss = Layers.mse(model.forward(x), t);
                loss.backward();
                adam.step();
                curve[e] = loss.data[0];
            }
            return new Outcome(curve, curve[epochs - 1], epochs, model.params().size());
        }
    }
}

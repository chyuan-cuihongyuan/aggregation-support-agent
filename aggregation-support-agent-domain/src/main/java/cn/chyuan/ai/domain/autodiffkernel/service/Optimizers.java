package cn.chyuan.ai.domain.autodiffkernel.service;

import java.util.List;

/**
 * 优化器（工单 0659 BZ5，pytorch 思想）。
 * SGD 动量/Adam（一阶二阶矩+偏置校正）/参数组与超参/step 更新与梯度清零。
 */
interface Optimizer {

    /** 按当前梯度更新参数 */
    void step();

    /** 梯度清零（下轮反向传播前调用） */
    void zeroGrad();

    static Optimizer sgd(List<Tensor> params, double lr, double momentum) {
        if (lr <= 0 || momentum < 0) {
            throw new IllegalArgumentException("SGD 超参非法");
        }
        return new Sgd(params, lr, momentum);
    }

    static Optimizer adam(List<Tensor> params, double lr, double beta1, double beta2, double eps) {
        if (lr <= 0 || beta1 <= 0 || beta1 >= 1 || beta2 <= 0 || beta2 >= 1 || eps <= 0) {
            throw new IllegalArgumentException("Adam 超参非法");
        }
        return new Adam(params, lr, beta1, beta2, eps);
    }

    final class Sgd implements Optimizer {
        private final List<Tensor> params;
        private final double lr;
        private final double momentum;
        private final double[][] velocity;

        Sgd(List<Tensor> params, double lr, double momentum) {
            this.params = List.copyOf(params);
            this.lr = lr;
            this.momentum = momentum;
            this.velocity = new double[params.size()][];
            for (int i = 0; i < params.size(); i++) {
                this.velocity[i] = new double[params.get(i).data.length];
            }
        }

        @Override
        public void step() {
            for (int p = 0; p < params.size(); p++) {
                Tensor t = params.get(p);
                if (t.grad == null) {
                    continue;
                }
                for (int i = 0; i < t.data.length; i++) {
                    velocity[p][i] = momentum * velocity[p][i] + t.grad[i];
                    t.data[i] -= lr * velocity[p][i];
                }
            }
        }

        @Override
        public void zeroGrad() {
            for (Tensor t : params) {
                t.grad = null;
            }
        }
    }

    final class Adam implements Optimizer {
        private final List<Tensor> params;
        private final double lr;
        private final double beta1;
        private final double beta2;
        private final double eps;
        private final double[][] m;
        private final double[][] v;
        private long t;

        Adam(List<Tensor> params, double lr, double beta1, double beta2, double eps) {
            this.params = List.copyOf(params);
            this.lr = lr;
            this.beta1 = beta1;
            this.beta2 = beta2;
            this.eps = eps;
            this.m = new double[params.size()][];
            this.v = new double[params.size()][];
            for (int i = 0; i < params.size(); i++) {
                this.m[i] = new double[params.get(i).data.length];
                this.v[i] = new double[params.get(i).data.length];
            }
        }

        @Override
        public void step() {
            t++;
            double bc1 = 1 - Math.pow(beta1, t);
            double bc2 = 1 - Math.pow(beta2, t);
            for (int p = 0; p < params.size(); p++) {
                Tensor tensor = params.get(p);
                if (tensor.grad == null) {
                    continue;
                }
                for (int i = 0; i < tensor.data.length; i++) {
                    double g = tensor.grad[i];
                    m[p][i] = beta1 * m[p][i] + (1 - beta1) * g;
                    v[p][i] = beta2 * v[p][i] + (1 - beta2) * g * g;
                    double mHat = m[p][i] / bc1;
                    double vHat = v[p][i] / bc2;
                    tensor.data[i] -= lr * mHat / (Math.sqrt(vHat) + eps);
                }
            }
        }

        @Override
        public void zeroGrad() {
            for (Tensor tensor : params) {
                tensor.grad = null;
            }
        }
    }
}

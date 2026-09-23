package cn.chyuan.ai.domain.autodiffkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 经典层与损失（工单 0658 BZ4，pytorch 思想）。
 * 线性层（W·x+b，Xavier 均匀初始化）/ReLU/Tanh/Sigmoid/
 * MSE 损失（全元素均值）/数值稳定 Softmax（行 max 减除，Jacobian 反传）。
 */
public final class Layers {

    private Layers() {
    }

    /** 线性层：weight [in,out]，bias [1,out] 行广播 */
    public static final class Linear {
        private final Tensor w;
        private final Tensor b;

        public Linear(int in, int out, Random random) {
            if (in <= 0 || out <= 0) {
                throw new IllegalArgumentException("线性层维度必须为正");
            }
            double lim = Math.sqrt(6.0d / (in + out));
            double[] wd = new double[in * out];
            double[] bd = new double[out];
            for (int i = 0; i < wd.length; i++) {
                wd[i] = -lim + 2 * lim * random.nextDouble();
            }
            for (int i = 0; i < bd.length; i++) {
                bd[i] = -lim + 2 * lim * random.nextDouble();
            }
            this.w = Tensor.parameter(wd, in, out);
            this.b = Tensor.parameter(bd, 1, out);
        }

        public Tensor forward(Tensor x) {
            return x.matmul(w).add(b);
        }

        public List<Tensor> params() {
            return List.of(w, b);
        }
    }

    public static Tensor relu(Tensor x) {
        Tensor out = new Tensor(new double[x.data.length], x.shape.clone());
        for (int i = 0; i < x.data.length; i++) {
            out.data[i] = Math.max(0, x.data[i]);
        }
        out.wire(new Tensor[]{x}, () -> {
            for (int i = 0; i < x.data.length; i++) {
                if (x.data[i] > 0) {
                    accum(x, i, out.grad[i]);
                }
            }
        });
        return out;
    }

    public static Tensor tanh(Tensor x) {
        Tensor out = new Tensor(new double[x.data.length], x.shape.clone());
        for (int i = 0; i < x.data.length; i++) {
            out.data[i] = Math.tanh(x.data[i]);
        }
        out.wire(new Tensor[]{x}, () -> {
            for (int i = 0; i < x.data.length; i++) {
                double y = out.data[i];
                accum(x, i, out.grad[i] * (1 - y * y));
            }
        });
        return out;
    }

    public static Tensor sigmoid(Tensor x) {
        Tensor out = new Tensor(new double[x.data.length], x.shape.clone());
        for (int i = 0; i < x.data.length; i++) {
            double v = x.data[i];
            out.data[i] = v >= 0 ? 1 / (1 + Math.exp(-v)) : Math.exp(v) / (1 + Math.exp(v));
        }
        out.wire(new Tensor[]{x}, () -> {
            for (int i = 0; i < x.data.length; i++) {
                double y = out.data[i];
                accum(x, i, out.grad[i] * y * (1 - y));
            }
        });
        return out;
    }

    /** 行方向稳定 Softmax（仅支持二维 [rows, classes]） */
    public static Tensor softmax(Tensor x) {
        if (x.shape.length != 2) {
            throw new IllegalArgumentException("softmax 仅支持二维张量");
        }
        int rows = x.shape[0];
        int cols = x.shape[1];
        Tensor out = new Tensor(new double[rows * cols], x.shape.clone());
        for (int r = 0; r < rows; r++) {
            double max = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < cols; c++) {
                max = Math.max(max, x.data[r * cols + c]);
            }
            double sum = 0;
            for (int c = 0; c < cols; c++) {
                double e = Math.exp(x.data[r * cols + c] - max);
                out.data[r * cols + c] = e;
                sum += e;
            }
            for (int c = 0; c < cols; c++) {
                out.data[r * cols + c] /= sum;
            }
        }
        out.wire(new Tensor[]{x}, () -> {
            for (int r = 0; r < rows; r++) {
                double dot = 0;
                for (int c = 0; c < cols; c++) {
                    dot += out.grad[r * cols + c] * out.data[r * cols + c];
                }
                for (int c = 0; c < cols; c++) {
                    double y = out.data[r * cols + c];
                    accum(x, r * cols + c, y * (out.grad[r * cols + c] - dot));
                }
            }
        });
        return out;
    }

    /** MSE：全元素均值的 [1] 标量 */
    public static Tensor mse(Tensor pred, Tensor target) {
        if (pred == null || target == null || !java.util.Arrays.equals(pred.shape, target.shape)) {
            throw new IllegalArgumentException("MSE 形状必须一致");
        }
        int n = pred.data.length;
        Tensor out = new Tensor(new double[1], new int[]{1});
        double s = 0;
        for (int i = 0; i < n; i++) {
            double d = pred.data[i] - target.data[i];
            s += d * d;
        }
        out.data[0] = s / n;
        out.wire(new Tensor[]{pred, target}, () -> {
            double g = out.grad[0];
            for (int i = 0; i < n; i++) {
                double d = pred.data[i] - target.data[i];
                accum(pred, i, g * 2 * d / n);
                accum(target, i, -g * 2 * d / n);
            }
        });
        return out;
    }

    private static void accum(Tensor t, int flat, double v) {
        if (t.grad != null) {
            t.grad[flat] += v;
        }
    }

    static List<Tensor> concat(List<Tensor> a, List<Tensor> b) {
        List<Tensor> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}

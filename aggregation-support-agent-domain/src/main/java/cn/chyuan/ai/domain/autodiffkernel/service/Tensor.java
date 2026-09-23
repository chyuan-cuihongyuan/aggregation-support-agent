package cn.chyuan.ai.domain.autodiffkernel.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 张量与自动微分核心（工单 0655-0657 BZ1-BZ3，pytorch 思想）。
 * 多维 double 数据+shape/右对齐广播逐元素四则（形状不兼容拒绝）/
 * 前向计算图节点记录父引用（topo 去重不重复建图）/环路引用拒绝/
 * 拓扑逆序反向传播链式法则/多路径梯度累加/广播梯度规约（unbroadcast）。
 */
public final class Tensor {

    final double[] data;
    final int[] shape;
    double[] grad;
    Tensor[] inputs = EMPTY;
    private Runnable backwardFn;
    boolean requiresGrad;

    private static final Tensor[] EMPTY = new Tensor[0];

    Tensor(double[] data, int[] shape) {
        this.data = data;
        this.shape = shape;
    }

    public static Tensor of(double[] data, int... shape) {
        if (data == null || shape == null || shape.length == 0) {
            throw new IllegalArgumentException("张量数据与形状不得为空");
        }
        int total = 1;
        for (int dim : shape) {
            if (dim <= 0) {
                throw new IllegalArgumentException("张量维度必须为正");
            }
            total *= dim;
        }
        if (data.length != total) {
            throw new IllegalArgumentException("数据长度与形状不匹配");
        }
        return new Tensor(data.clone(), shape.clone());
    }

    /** 参数节点（参与求导） */
    public static Tensor parameter(double[] data, int... shape) {
        Tensor t = of(data, shape);
        t.requiresGrad = true;
        return t;
    }

    public static Tensor fill(double v, int... shape) {
        int total = 1;
        for (int dim : shape) {
            total *= dim;
        }
        double[] data = new double[total];
        Arrays.fill(data, v);
        return of(data, shape);
    }

    public double[] data() {
        return data.clone();
    }

    public double[] grad() {
        return grad == null ? null : grad.clone();
    }

    public int[] shape() {
        return shape.clone();
    }

    public double get(int... idx) {
        return data[flatIndex(shape, idx)];
    }

    /** 低层接线钩子（供高级用法手工建边，可构造环路用于拒绝测试） */
    public void linkParent(Tensor parent) {
        if (parent == null) {
            throw new IllegalArgumentException("父节点不得为 null");
        }
        this.inputs = new Tensor[]{parent};
        this.backwardFn = () -> {
        };
    }

    /** 计算图去重节点计数（topo 去重口径：被引用多次的节点只计一次） */
    public static int nodeCount(Tensor root) {
        return topoSort(root).size();
    }

    // ---------------------------------------------------------------- 广播逐元素

    public Tensor add(Tensor o) {
        return binary(o, (a, b) -> a + b, (out, a, b) -> forEachIndex(out.shape, idx -> {
            double g = out.grad[flatIndex(out.shape, idx)];
            a.accum(flatIndexBroadcast(a.shape, idx, out.shape), g);
            b.accum(flatIndexBroadcast(b.shape, idx, out.shape), g);
        }));
    }

    public Tensor sub(Tensor o) {
        return binary(o, (a, b) -> a - b, (out, a, b) -> forEachIndex(out.shape, idx -> {
            double g = out.grad[flatIndex(out.shape, idx)];
            a.accum(flatIndexBroadcast(a.shape, idx, out.shape), g);
            b.accum(flatIndexBroadcast(b.shape, idx, out.shape), -g);
        }));
    }

    public Tensor mul(Tensor o) {
        return binary(o, (a, b) -> a * b, (out, a, b) -> forEachIndex(out.shape, idx -> {
            int flat = flatIndex(out.shape, idx);
            double g = out.grad[flat];
            a.accum(flatIndexBroadcast(a.shape, idx, out.shape), g * b.data[flatIndexBroadcast(b.shape, idx, out.shape)]);
            b.accum(flatIndexBroadcast(b.shape, idx, out.shape), g * a.data[flatIndexBroadcast(a.shape, idx, out.shape)]);
        }));
    }

    public Tensor div(Tensor o) {
        return binary(o, (a, b) -> a / b, (out, a, b) -> forEachIndex(out.shape, idx -> {
            int flat = flatIndex(out.shape, idx);
            double g = out.grad[flat];
            double bv = b.data[flatIndexBroadcast(b.shape, idx, out.shape)];
            double av = a.data[flatIndexBroadcast(a.shape, idx, out.shape)];
            a.accum(flatIndexBroadcast(a.shape, idx, out.shape), g / bv);
            b.accum(flatIndexBroadcast(b.shape, idx, out.shape), -g * av / (bv * bv));
        }));
    }

    private Tensor binary(Tensor o, java.util.function.DoubleBinaryOperator op, TernaryBackward backward) {
        if (o == null) {
            throw new IllegalArgumentException("运算对象不得为 null");
        }
        int[] rs = broadcastShapes(this.shape, o.shape);
        Tensor out = new Tensor(new double[numel(rs)], rs);
        forEachIndex(rs, idx -> out.data[flatIndex(rs, idx)] = op.applyAsDouble(
                this.data[flatIndexBroadcast(this.shape, idx, rs)],
                o.data[flatIndexBroadcast(o.shape, idx, rs)]));
        out.wire(new Tensor[]{this, o}, () -> backward.accept(out, this, o));
        return out;
    }

    private interface TernaryBackward {
        void accept(Tensor out, Tensor a, Tensor b);
    }

    // ---------------------------------------------------------------- 一元与归约

    public Tensor exp() {
        Tensor out = new Tensor(new double[data.length], shape.clone());
        for (int i = 0; i < data.length; i++) {
            out.data[i] = Math.exp(data[i]);
        }
        out.wire(new Tensor[]{this}, () -> {
            for (int i = 0; i < data.length; i++) {
                accumFrom(this, i, out.grad[i] * out.data[i]);
            }
        });
        return out;
    }

    public Tensor log() {
        Tensor out = new Tensor(new double[data.length], shape.clone());
        for (int i = 0; i < data.length; i++) {
            if (data[i] <= 0) {
                throw new IllegalArgumentException("log 仅支持正数域");
            }
            out.data[i] = Math.log(data[i]);
        }
        out.wire(new Tensor[]{this}, () -> {
            for (int i = 0; i < data.length; i++) {
                accumFrom(this, i, out.grad[i] / data[i]);
            }
        });
        return out;
    }

    /** 全量归约为 [1] 标量 */
    public Tensor sum() {
        Tensor out = new Tensor(new double[1], new int[]{1});
        double s = 0;
        for (double v : data) {
            s += v;
        }
        out.data[0] = s;
        out.wire(new Tensor[]{this}, () -> {
            for (int i = 0; i < data.length; i++) {
                accumFrom(this, i, out.grad[0]);
            }
        });
        return out;
    }

    public Tensor matmul(Tensor o) {
        if (o == null || shape.length != 2 || o.shape.length != 2) {
            throw new IllegalArgumentException("matmul 仅支持二维张量");
        }
        if (shape[1] != o.shape[0]) {
            throw new IllegalArgumentException("matmul 内维不匹配");
        }
        int m = shape[0];
        int k = shape[1];
        int n = o.shape[1];
        Tensor out = new Tensor(new double[m * n], new int[]{m, n});
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double s = 0;
                for (int p = 0; p < k; p++) {
                    s += data[i * k + p] * o.data[p * n + j];
                }
                out.data[i * n + j] = s;
            }
        }
        out.wire(new Tensor[]{this, o}, () -> {
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < n; j++) {
                    double g = out.grad[i * n + j];
                    for (int p = 0; p < k; p++) {
                        accumFrom(this, i * k + p, g * o.data[p * n + j]);
                        accumFrom(o, p * n + j, g * data[i * k + p]);
                    }
                }
            }
        });
        return out;
    }

    // ---------------------------------------------------------------- 反向传播

    /** 拓扑逆序反向传播：种子梯度 1，链式法则逐层回传（多路径累加） */
    public void backward() {
        List<Tensor> topo = topoSort(this);
        for (Tensor t : topo) {
            if (t.requiresGrad) {
                t.grad = new double[t.data.length];
            }
        }
        if (grad == null) {
            throw new IllegalArgumentException("根节点不参与求导（requiresGrad=false）");
        }
        Arrays.fill(grad, 1.0d);
        for (int i = topo.size() - 1; i >= 0; i--) {
            Tensor t = topo.get(i);
            if (t.backwardFn != null) {
                t.backwardFn.run();
            }
        }
    }

    private static List<Tensor> topoSort(Tensor root) {
        List<Tensor> order = new ArrayList<>();
        Map<Tensor, Boolean> visited = new IdentityHashMap<>();
        Map<Tensor, Boolean> visiting = new IdentityHashMap<>();
        dfs(root, visiting, visited, order);
        return order;
    }

    private static void dfs(Tensor t, Map<Tensor, Boolean> visiting, Map<Tensor, Boolean> visited, List<Tensor> order) {
        if (visited.containsKey(t)) {
            return;
        }
        if (visiting.containsKey(t)) {
            throw new IllegalArgumentException("计算图存在环路引用");
        }
        visiting.put(t, Boolean.TRUE);
        for (Tensor in : t.inputs) {
            dfs(in, visiting, visited, order);
        }
        visiting.remove(t);
        visited.put(t, Boolean.TRUE);
        order.add(t);
    }

    void wire(Tensor[] ins, Runnable backward) {
        boolean any = false;
        for (Tensor in : ins) {
            any |= in.requiresGrad;
        }
        this.requiresGrad = any;
        if (any) {
            this.inputs = ins.clone();
            this.backwardFn = backward;
        }
    }

    private static void accumFrom(Tensor target, int flat, double v) {
        if (target.grad != null) {
            target.grad[flat] += v;
        }
    }

    void accum(int flat, double v) {
        if (grad != null) {
            grad[flat] += v;
        }
    }

    // ---------------------------------------------------------------- 形状工具

    void requireFinite() {
        for (double v : data) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("张量含 NaN/Inf 数值");
            }
        }
    }

    static int[] broadcastShapes(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        int[] rs = new int[len];
        for (int d = 0; d < len; d++) {
            int da = d >= len - a.length ? a[d - (len - a.length)] : 1;
            int db = d >= len - b.length ? b[d - (len - b.length)] : 1;
            if (da != db && da != 1 && db != 1) {
                throw new IllegalArgumentException("广播形状不兼容");
            }
            rs[d] = Math.max(da, db);
        }
        return rs;
    }

    static int numel(int[] shape) {
        int total = 1;
        for (int dim : shape) {
            total *= dim;
        }
        return total;
    }

    static int flatIndex(int[] shape, int... idx) {
        if (idx.length != shape.length) {
            throw new IllegalArgumentException("索引维度不匹配");
        }
        int flat = 0;
        for (int d = 0; d < shape.length; d++) {
            if (idx[d] < 0 || idx[d] >= shape[d]) {
                throw new IllegalArgumentException("索引越界");
            }
            flat = flat * shape[d] + idx[d];
        }
        return flat;
    }

    /** 结果多下标映射到目标形状（广播维度取 0），unbroadcast 求和即多次累加 */
    static int flatIndexBroadcast(int[] targetShape, int[] resIdx, int[] resShape) {
        int lead = resShape.length - targetShape.length;
        int flat = 0;
        for (int d = 0; d < targetShape.length; d++) {
            int dim = targetShape[d];
            int i = dim == 1 ? 0 : resIdx[lead + d];
            flat = flat * dim + i;
        }
        return flat;
    }

    static void forEachIndex(int[] shape, Consumer<int[]> body) {
        int[] idx = new int[shape.length];
        int total = numel(shape);
        for (int c = 0; c < total; c++) {
            body.accept(idx);
            for (int d = shape.length - 1; d >= 0; d--) {
                if (++idx[d] < shape[d]) {
                    break;
                }
                idx[d] = 0;
            }
        }
    }
}

package cn.chyuan.ai.domain.autodiffkernel.service;

import java.util.function.Supplier;

/**
 * 数值校验（工单 0660 BZ6，pytorch 思想）。
 * 中心有限差分梯度对照（相对误差容差断言）/张量 NaN-Inf 检测拒绝。
 */
public final class Gradients {

    private Gradients() {
    }

    /**
     * 有限差分对照：解析梯度经 backward 取得后，逐元素中心差分
     * （(L(w+e)-L(w-e))/(2e)）重建损失图对比，返回最大归一化绝对误差。
     */
    public static double maxRelError(Tensor param, Supplier<Tensor> lossFn, double eps) {
        if (param == null || lossFn == null || eps <= 0) {
            throw new IllegalArgumentException("数值校验参数非法");
        }
        Tensor loss = lossFn.get();
        loss.backward();
        double[] analytic = param.grad;
        if (analytic == null) {
            throw new IllegalArgumentException("参数未参与求导");
        }
        double maxErr = 0;
        for (int i = 0; i < param.data.length; i++) {
            double orig = param.data[i];
            param.data[i] = orig + eps;
            double lp = lossFn.get().data[0];
            param.data[i] = orig - eps;
            double lm = lossFn.get().data[0];
            param.data[i] = orig;
            double numeric = (lp - lm) / (2 * eps);
            double err = Math.abs(numeric - analytic[i]) / Math.max(1.0d, Math.abs(numeric));
            maxErr = Math.max(maxErr, err);
        }
        return maxErr;
    }

    /** 张量数据 NaN/Inf 检测（含 Inf 时拒绝） */
    public static void requireFinite(Tensor t) {
        if (t == null) {
            throw new IllegalArgumentException("张量不得为 null");
        }
        t.requireFinite();
    }
}

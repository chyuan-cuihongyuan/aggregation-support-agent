package cn.chyuan.ai.domain.autodiffkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 训练循环（工单 0661 BZ7，pytorch 思想）。
 * epoch/batch 切分（种子可复现 Fisher-Yates shuffle）/损失曲线登记/
 * 每轮 zeroGrad→forward→backward→step 标准循环。
 */
public final class Trainer {

    private Trainer() {
    }

    /** 一轮记录：epoch 序号与该轮损失 */
    public record Epoch(int epoch, double loss) {
    }

    /** 全量批训练：每 epoch 重建计算图求损失，回传后步进优化器 */
    public static List<Epoch> fit(Supplier<Tensor> lossFn, List<Tensor> params, Optimizer opt, int epochs) {
        if (lossFn == null || params == null || opt == null || epochs <= 0) {
            throw new IllegalArgumentException("训练参数非法");
        }
        List<Epoch> curve = new ArrayList<>(epochs);
        for (int e = 0; e < epochs; e++) {
            opt.zeroGrad();
            Tensor loss = lossFn.get();
            loss.backward();
            opt.step();
            curve.add(new Epoch(e, loss.data[0]));
        }
        return curve;
    }

    /** 样本下标批切分：种子可复现 shuffle 后按 batchSize 分片（末批可短） */
    public static int[][] batches(int n, int batchSize, Random random) {
        if (n <= 0 || batchSize <= 0 || random == null) {
            throw new IllegalArgumentException("批切分参数非法");
        }
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = idx[i];
            idx[i] = idx[j];
            idx[j] = tmp;
        }
        int batchCount = (n + batchSize - 1) / batchSize;
        int[][] batches = new int[batchCount][];
        for (int b = 0; b < batchCount; b++) {
            int from = b * batchSize;
            int len = Math.min(batchSize, n - from);
            batches[b] = new int[len];
            System.arraycopy(idx, from, batches[b], 0, len);
        }
        return batches;
    }
}

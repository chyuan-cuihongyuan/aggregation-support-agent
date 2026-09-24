package cn.chyuan.ai.domain.kvkernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 水位与统计（工单 0753 CK6，vllm PagedAttention 思想）。
 * 块利用率/水位阈值模拟（超阈值拒绝新准入）/每序列占用/分配失败计数。
 */
public final class Watermark {

    private final BlockPool pool;
    private final double admitThreshold;
    private long thresholdRejections;

    public Watermark(BlockPool pool, double admitThreshold) {
        if (admitThreshold <= 0 || admitThreshold > 1) {
            throw new IllegalArgumentException("水位阈值须在 (0,1]");
        }
        this.pool = pool;
        this.admitThreshold = admitThreshold;
    }

    /** 准入水位检查：利用率低于阈值才允许新序列准入 */
    public boolean canAdmit() {
        if (pool.utilization() >= admitThreshold) {
            thresholdRejections++;
            return false;
        }
        return true;
    }

    /** 每序列块占用 */
    public Map<Long, Integer> usagePerSeq(Iterable<SequenceBlocks> seqs) {
        Map<Long, Integer> usage = new HashMap<>();
        for (SequenceBlocks seq : seqs) {
            usage.put(seq.seqId(), seq.blocks().size());
        }
        return usage;
    }

    public double utilization() {
        return pool.utilization();
    }

    public double admitThreshold() {
        return admitThreshold;
    }

    public long thresholdRejections() {
        return thresholdRejections;
    }
}

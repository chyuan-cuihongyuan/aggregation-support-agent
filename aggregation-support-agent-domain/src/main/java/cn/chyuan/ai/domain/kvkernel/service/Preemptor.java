package cn.chyuan.ai.domain.kvkernel.service;

/**
 * 抢占与恢复（工单 0752 CK5，vllm PagedAttention 思想）。
 * 内存耗尽后进先出抢占/换出保留等待重算（recompute）/抢占统计。
 */
public final class Preemptor {

    private final BlockPool pool;
    private final ContinuousBatcher batcher;
    private long preemptions;
    private long recomputes;

    public Preemptor(BlockPool pool, ContinuousBatcher batcher) {
        this.pool = pool;
        this.batcher = batcher;
    }

    /** 抢占后进运行序列：释放块表，回等待队首（恢复=重算） */
    public ContinuousBatcher.Running preemptLast() {
        ContinuousBatcher.Running victim = batcher.lastRunning();
        if (victim == null) {
            throw new IllegalStateException("无运行序列可抢占");
        }
        batcher.removeRunning(victim);
        victim.blocks.releaseAll();
        batcher.pushWaitingFront(victim.blocks);
        preemptions++;
        return victim;
    }

    /** 恢复 = 重算：序列从空表重新 append 全部 token（等待队列队首优先准入） */
    public void recompute(SequenceBlocks seq, int tokens) {
        seq.append(tokens);
        recomputes++;
    }

    public long preemptions() {
        return preemptions;
    }

    public long recomputes() {
        return recomputes;
    }
}

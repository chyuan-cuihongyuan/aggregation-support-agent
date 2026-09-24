package cn.chyuan.ai.domain.kvkernel.service;

import java.util.List;

/**
 * 调度步进引擎（工单 0754 CK7，vllm PagedAttention 思想）。
 * step 准入+运行批演进/prefill 与 decode 混合/完成回收与 EOS 停止/抢占触发。
 */
public final class Engine {

    private final BlockPool pool;
    private final ContinuousBatcher batcher;
    private final Preemptor preemptor;
    private final int prefillTokens;
    private long steps;
    private long prefillTokensDone;
    private long decodeTokensDone;

    public Engine(BlockPool pool, ContinuousBatcher batcher, Preemptor preemptor, int prefillTokens) {
        if (prefillTokens <= 0) {
            throw new IllegalArgumentException("prefill 步长须为正");
        }
        this.pool = pool;
        this.batcher = batcher;
        this.preemptor = preemptor;
        this.prefillTokens = prefillTokens;
    }

    /** 一步：准入 → 运行批各序列推进（剩余>0），完成（EOS/剩余尽）回收；块耗尽抢占后进 */
    public void step(int eosSeqId) {
        batcher.admit();
        steps++;
        ContinuousBatcher.Running[] runnings = batcher.runningQueue().toArray(new ContinuousBatcher.Running[0]);
        for (ContinuousBatcher.Running r : runnings) {
            if (r.remaining <= 0) {
                finish(r, eosSeqId == r.seqId);
                continue;
            }
            try {
                int chunk = r.blocks.tokenCount() == 0 ? Math.min(prefillTokens, r.remaining) : 1;
                r.blocks.append(chunk);
                if (r.blocks.tokenCount() == chunk) {
                    prefillTokensDone += chunk;
                } else {
                    decodeTokensDone += chunk;
                }
                r.remaining -= chunk;
                if (r.remaining <= 0 || eosSeqId == r.seqId) {
                    finish(r, eosSeqId == r.seqId);
                }
            } catch (IllegalStateException e) {
                if (!pool.hasFree() && batcher.runningQueue().size() > 1) {
                    preemptor.preemptLast();
                } else {
                    throw e;
                }
            }
        }
    }

    private void finish(ContinuousBatcher.Running r, boolean eos) {
        r.remaining = 0;
        r.blocks.releaseAll();
        batcher.removeRunning(r);
    }

    /** 运行至全部完成 */
    public void runToCompletion() {
        int guard = 0;
        while (!batcher.runningQueue().isEmpty() || !batcher.waitingQueue().isEmpty()) {
            if (batcher.runningQueue().isEmpty() && !batcher.waitingQueue().isEmpty()) {
                batcher.admit();
                if (batcher.runningQueue().isEmpty()) {
                    return;
                }
            }
            step(-1);
            if (++guard > 100_000) {
                throw new IllegalStateException("步进不收敛");
            }
        }
    }

    public long steps() {
        return steps;
    }

    public long prefillTokensDone() {
        return prefillTokensDone;
    }

    public long decodeTokensDone() {
        return decodeTokensDone;
    }

    public List<Long> waitingIds() {
        return batcher.waitingQueue().stream().map(SequenceBlocks::seqId).toList();
    }
}

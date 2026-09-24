package cn.chyuan.ai.domain.kvkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 连续批处理准入（工单 0751 CK4，vllm PagedAttention 思想）。
 * 等待队列/运行批块数预算/迭代级有块即准入/预算不足排队 FIFO。
 */
public final class ContinuousBatcher {

    /** 批内序列：块表与剩余生成步 */
    public static final class Running {
        public final long seqId;
        public final SequenceBlocks blocks;
        public int remaining;

        Running(long seqId, SequenceBlocks blocks, int remaining) {
            this.seqId = seqId;
            this.blocks = blocks;
            this.remaining = remaining;
        }
    }

    private final Deque<SequenceBlocks> waiting = new ArrayDeque<>();
    private final Deque<Running> running = new ArrayDeque<>();
    private final BlockPool pool;
    private final int blockBudget;
    private long admitted;
    private long queuedRejections;

    public ContinuousBatcher(BlockPool pool, int blockBudget) {
        if (blockBudget <= 0) {
            throw new IllegalArgumentException("块预算须为正");
        }
        this.pool = pool;
        this.blockBudget = blockBudget;
    }

    public void enqueue(SequenceBlocks seq) {
        waiting.addLast(seq);
    }

    /** 迭代级准入：FIFO，预算内且池有空块即准入 */
    public void admit() {
        while (!waiting.isEmpty()) {
            if (running.size() * pool.blockSize() >= blockBudget) {
                queuedRejections++;
                return;
            }
            if (!pool.hasFree()) {
                queuedRejections++;
                return;
            }
            SequenceBlocks seq = waiting.pollFirst();
            running.addLast(new Running(seq.seqId(), seq, 0));
            admitted++;
        }
    }

    public Running find(long seqId) {
        for (Running r : running) {
            if (r.seqId == seqId) {
                return r;
            }
        }
        return null;
    }

    public void remove(Running r) {
        running.remove(r);
    }

    /** 抢占取后进（LIFO 尾部） */
    public Running lastRunning() {
        return running.peekLast();
    }

    public void pushWaitingFront(SequenceBlocks seq) {
        waiting.addFirst(seq);
    }

    public void removeRunning(Running r) {
        running.remove(r);
    }

    public Deque<SequenceBlocks> waitingQueue() {
        return waiting;
    }

    public Deque<Running> runningQueue() {
        return running;
    }

    public int blockBudget() {
        return blockBudget;
    }

    public long admitted() {
        return admitted;
    }

    public long queuedRejections() {
        return queuedRejections;
    }
}

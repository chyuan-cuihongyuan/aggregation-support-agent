package cn.chyuan.ai.domain.kvkernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 前缀共享 COW（工单 0750 CK3，vllm PagedAttention 思想）。
 * 相同前缀块引用共享计数/写时复制分叉新块/计数归零回收/共享节省统计。
 */
public final class PrefixCow {

    private final BlockPool pool;
    private final Map<Long, SequenceBlocks> tables = new HashMap<>();
    private long forkCount;
    private long cowCount;
    private long savedBlocks;

    public PrefixCow(BlockPool pool) {
        this.pool = pool;
    }

    /** 新序列空表 */
    public SequenceBlocks create(long seqId) {
        SequenceBlocks seq = new SequenceBlocks(pool, seqId);
        tables.put(seqId, seq);
        return seq;
    }

    /** fork：共享父序列前 atTokens 个 token 的整块（尾块不满不共享，由 COW 处理） */
    public SequenceBlocks fork(long childId, SequenceBlocks parent, int atTokens) {
        int sharedBlocks = atTokens / pool.blockSize();
        SequenceBlocks child = create(childId);
        for (int i = 0; i < sharedBlocks && i < parent.blocks().size(); i++) {
            child.attachShared(parent.blocks().get(i));
            savedBlocks++;
        }
        // 共享整块计数推进（不重复写槽，子序列下次写入走 COW）
        child.advanceTokens(sharedBlocks * pool.blockSize());
        forkCount++;
        return child;
    }

    /** 写时复制：序列尾块为共享块（引用>1）时分叉新块 */
    public void copyOnWrite(SequenceBlocks seq) {
        if (seq.blocks().isEmpty()) {
            throw new IllegalStateException("空块表无可写块");
        }
        int tail = seq.blocks().get(seq.blocks().size() - 1);
        if (pool.refCount(tail) > 1) {
            int newBlock = pool.allocate();
            int copiedFill = pool.fill(tail);
            seq.replaceTail(newBlock, copiedFill);
            cowCount++;
        }
    }

    /** 是否需要 COW（尾块被共享） */
    public boolean needsCow(SequenceBlocks seq) {
        if (seq.blocks().isEmpty()) {
            return false;
        }
        return pool.refCount(seq.blocks().get(seq.blocks().size() - 1)) > 1;
    }

    public SequenceBlocks table(long seqId) {
        SequenceBlocks seq = tables.get(seqId);
        if (seq == null) {
            throw new IllegalArgumentException("未知序列: " + seqId);
        }
        return seq;
    }

    public long forkCount() {
        return forkCount;
    }

    public long cowCount() {
        return cowCount;
    }

    public long savedBlocks() {
        return savedBlocks;
    }
}

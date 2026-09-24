package cn.chyuan.ai.domain.kvkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 序列块表（工单 0749 CK2，vllm PagedAttention 思想）。
 * 逻辑→物理块映射/append 增长按需分配/截断释放尾部/位置寻址（块号+槽内偏移）。
 */
public final class SequenceBlocks {

    /** 位置寻址结果：物理块 id 与槽内偏移 */
    public record Location(int physicalBlock, int slotOffset) {
    }

    private final BlockPool pool;
    private final long seqId;
    private final List<Integer> blocks = new ArrayList<>();
    private int tokenCount;

    public SequenceBlocks(BlockPool pool, long seqId) {
        this.pool = pool;
        this.seqId = seqId;
    }

    public long seqId() {
        return seqId;
    }

    public int tokenCount() {
        return tokenCount;
    }

    public List<Integer> blocks() {
        return List.copyOf(blocks);
    }

    /** 是否需要新块（当前尾块已满或无块） */
    public boolean needsNewBlock() {
        if (blocks.isEmpty()) {
            return true;
        }
        return pool.fill(blocks.get(blocks.size() - 1)) >= pool.blockSize();
    }

    /** 追加 n 个 token 槽位：按需分配新块（分配失败向上抛由抢占处理） */
    public void append(int n) {
        for (int i = 0; i < n; i++) {
            if (needsNewBlock()) {
                blocks.add(pool.allocate());
            }
            int tail = blocks.get(blocks.size() - 1);
            pool.writeSlot(tail);
            tokenCount++;
        }
    }

    /** 截断到 newLen（token 数）：释放不再覆盖的尾部块 */
    public void truncate(int newLen) {
        if (newLen < 0 || newLen > tokenCount) {
            throw new IllegalArgumentException("非法截断长度: " + newLen);
        }
        int keepBlocks = newLen == 0 ? 0 : (newLen - 1) / pool.blockSize() + 1;
        while (blocks.size() > keepBlocks) {
            pool.release(blocks.remove(blocks.size() - 1));
        }
        tokenCount = newLen;
    }

    /** 全部释放 */
    public void releaseAll() {
        truncate(0);
    }

    /** 位置寻址 */
    public Location locate(int pos) {
        if (pos < 0 || pos >= tokenCount) {
            throw new IllegalArgumentException("位置越界: " + pos);
        }
        return new Location(blocks.get(pos / pool.blockSize()), pos % pool.blockSize());
    }

    /** 共享引用块登记（fork 用，不新分配） */
    void attachShared(int physicalBlock) {
        pool.addRef(physicalBlock);
        blocks.add(physicalBlock);
    }

    int sharedTailFill() {
        return blocks.isEmpty() ? 0 : pool.fill(blocks.get(blocks.size() - 1));
    }

    /** 写尾槽（COW 由 PrefixCow 决定块归属） */
    void writeTo(int blockIndex) {
        pool.writeSlot(blocks.get(blockIndex));
        tokenCount++;
    }

    /** 共享满块计数推进（fork 复制内容不重复写槽） */
    void advanceTokens(int n) {
        if (tokenCount + n > blocks.size() * pool.blockSize()) {
            throw new IllegalArgumentException("推进越界");
        }
        tokenCount += n;
    }

    /** 尾块替换（COW 分叉）：旧块减引用，换新块并复制填充度 */
    void replaceTail(int newBlock, int copiedFill) {
        int old = blocks.set(blocks.size() - 1, newBlock);
        pool.release(old);
        for (int i = 0; i < copiedFill; i++) {
            pool.writeSlot(newBlock);
        }
    }
}

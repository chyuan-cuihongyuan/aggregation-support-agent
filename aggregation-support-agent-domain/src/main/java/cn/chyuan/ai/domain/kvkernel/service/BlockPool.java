package cn.chyuan.ai.domain.kvkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 物理块池（工单 0748 CK1，vllm PagedAttention 思想）。
 * 固定槽位块（block_size 可配）/分配释放与空闲链/耗尽拒绝/占用统计与引用计数。
 */
public final class BlockPool {

    private final int blockSize;
    private final int capacity;
    private final Deque<Integer> freeIds = new ArrayDeque<>();
    private final Map<Integer, Integer> refCounts = new HashMap<>();
    private final Map<Integer, Integer> fills = new HashMap<>();
    private long allocations;
    private long releases;
    private long exhaustedRejections;

    public BlockPool(int blockSize, int capacity) {
        if (blockSize <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("块大小与容量须为正");
        }
        this.blockSize = blockSize;
        this.capacity = capacity;
        for (int i = capacity - 1; i >= 0; i--) {
            freeIds.push(i);
        }
    }

    public int blockSize() {
        return blockSize;
    }

    public int capacity() {
        return capacity;
    }

    public boolean hasFree() {
        return !freeIds.isEmpty();
    }

    public int freeCount() {
        return freeIds.size();
    }

    /** 分配：耗尽拒绝（调用方触发抢占） */
    public int allocate() {
        Integer id = freeIds.poll();
        if (id == null) {
            exhaustedRejections++;
            throw new IllegalStateException("块池耗尽");
        }
        refCounts.put(id, 1);
        fills.put(id, 0);
        allocations++;
        return id;
    }

    /** 释放：引用计数归零才回空闲链 */
    public void release(int id) {
        int refs = refCounts.getOrDefault(id, 0);
        if (refs <= 0) {
            throw new IllegalStateException("重复释放块: " + id);
        }
        if (refs == 1) {
            refCounts.remove(id);
            fills.remove(id);
            freeIds.push(id);
            releases++;
        } else {
            refCounts.put(id, refs - 1);
            releases++;
        }
    }

    public void addRef(int id) {
        checkLive(id);
        refCounts.merge(id, 1, Integer::sum);
    }

    public int refCount(int id) {
        return refCounts.getOrDefault(id, 0);
    }

    public int fill(int id) {
        checkLive(id);
        return fills.get(id);
    }

    /** 写入一槽：填满为止，越界拒绝 */
    public void writeSlot(int id) {
        checkLive(id);
        int current = fills.get(id);
        if (current >= blockSize) {
            throw new IllegalStateException("块已满: " + id);
        }
        fills.put(id, current + 1);
    }

    public int usedBlocks() {
        return capacity - freeIds.size();
    }

    public double utilization() {
        return (double) usedBlocks() / capacity;
    }

    public long exhaustedRejections() {
        return exhaustedRejections;
    }

    public long allocations() {
        return allocations;
    }

    private void checkLive(int id) {
        if (!refCounts.containsKey(id)) {
            throw new IllegalArgumentException("非在用块: " + id);
        }
    }
}

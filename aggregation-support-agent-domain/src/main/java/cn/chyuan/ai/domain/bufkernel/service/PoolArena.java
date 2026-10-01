package cn.chyuan.ai.domain.bufkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 池化 arena（工单 1197 FE2 / 1198 FE3，netty PoolArena 思想）。
 * chunk 内偏移单调推进分配，chunk 不足开新 chunk，超总量拒绝；
 * 归还进尺寸分级池（规范化为 2 的幂，下限 16），同尺寸再分配 LIFO 复用；重复归还拒绝；
 * 切片共享底层独立读写位：retainedSlice 独立引用计数扣父，共享切片随父存亡不可独立释放。
 */
public final class PoolArena {

    static final int MIN_CLASS = 16;

    private final int chunkSize;
    private final int maxChunks;
    private final List<byte[]> chunks = new ArrayList<>();
    private int chunkOffset;
    private final Map<Integer, ByteBuf> bufs = new LinkedHashMap<>();
    private final Map<Integer, Deque<Integer>> pools = new HashMap<>();
    private int seq;

    public PoolArena(int chunkSize, int maxChunks) {
        if (chunkSize <= 0 || maxChunks <= 0) {
            throw new IllegalArgumentException("chunk 配置须为正");
        }
        this.chunkSize = chunkSize;
        this.maxChunks = maxChunks;
    }

    /** 尺寸分级：向上取 2 的幂，下限 16；超 chunk 上限拒绝 */
    public int sizeClass(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("分配尺寸须为正: " + size);
        }
        if (size > chunkSize) {
            throw new IllegalArgumentException("尺寸超最大分级: " + size);
        }
        int cls = MIN_CLASS;
        while (cls < size) {
            cls <<= 1;
        }
        return cls;
    }

    /** 分配：分级池 LIFO 复用优先，否则 chunk 内切新 */
    public ByteBuf allocate(int size) {
        int cls = sizeClass(size);
        Deque<Integer> pool = pools.get(cls);
        if (pool != null && !pool.isEmpty()) {
            return reset(buf(pool.removeLast()));
        }
        if (chunks.isEmpty() || chunkOffset + cls > chunkSize) {
            if (!chunks.isEmpty() && chunks.size() >= maxChunks) {
                throw new IllegalStateException("arena 容量耗尽");
            }
            chunks.add(new byte[chunkSize]);
            chunkOffset = 0;
        }
        ByteBuf buf = new ByteBuf(++seq, chunks.getLast(), chunkOffset, cls, 0);
        chunkOffset += cls;
        bufs.put(buf.id, buf);
        return buf;
    }

    public ByteBuf buf(int id) {
        ByteBuf buf = bufs.get(id);
        if (buf == null) {
            throw new IllegalArgumentException("未知缓冲: " + id);
        }
        return buf;
    }

    /** 复用重置：引用归一、存活、读写位归零 */
    ByteBuf reset(ByteBuf buf) {
        buf.refs = 1;
        buf.freed = false;
        buf.readerIndex = 0;
        buf.writerIndex = 0;
        return buf;
    }

    /** 归还入分级池（LIFO）；重复归还拒绝 */
    public void recycle(ByteBuf buf) {
        if (buf.refs != 0 || !buf.freed) {
            throw new IllegalArgumentException("重复归还: " + buf.id);
        }
        pools.computeIfAbsent(buf.capacity, k -> new ArrayDeque<>()).addLast(buf.id);
    }

    /** 切片：共享底层，读写位独立；retained 独立引用（父 refs+1） */
    public ByteBuf slice(ByteBuf parent, int index, int length, boolean retained) {
        assertAlive(parent);
        if (index < 0 || length < 0 || index + length > parent.capacity) {
            throw new IllegalArgumentException("切片越界");
        }
        if (retained) {
            parent.refs++;
        }
        ByteBuf slice = new ByteBuf(++seq, parent.memory, parent.offset + index, length,
                retained ? -parent.id : parent.id);
        slice.writerIndex = length;
        bufs.put(slice.id, slice);
        return slice;
    }

    /** 释放：本体归零回收入池；retainedSlice 扣父引用；共享切片不可独立释放 */
    public boolean release(ByteBuf buf) {
        assertAlive(buf);
        if (buf.parent > 0) {
            throw new IllegalStateException("共享切片无独立引用，随父释放: " + buf.id);
        }
        if (buf.parent < 0) {
            buf.freed = true;
            ByteBuf parent = bufs.get(-buf.parent);
            return parent != null && parent.release();
        }
        return buf.release();
    }

    /** 存活校验：本体未释放，且切片父仍存活 */
    public void assertAlive(ByteBuf buf) {
        if (buf.freed) {
            throw new IllegalStateException("缓冲已释放: " + buf.id);
        }
        if (buf.parent != 0) {
            ByteBuf parent = bufs.get(Math.abs(buf.parent));
            if (parent == null || parent.freed) {
                throw new IllegalStateException("父缓冲已释放: " + buf.id);
            }
        }
    }

    /** 引用计数保留 */
    public void retain(ByteBuf buf) {
        assertAlive(buf);
        buf.refs++;
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** 已用字节数（满 chunk 全额 + 当前 chunk 偏移） */
    public int usedBytes() {
        int total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            total += (i == chunks.size() - 1) ? chunkOffset : chunkSize;
        }
        return total;
    }
}

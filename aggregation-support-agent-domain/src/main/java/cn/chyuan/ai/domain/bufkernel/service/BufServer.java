package cn.chyuan.ai.domain.bufkernel.service;

import java.util.List;

/**
 * 内存池组合实现（工单 1203 FE8，netty 思想）。
 * 分配先查线程本地缓存，未命中走 arena（分级池 LIFO 复用 → chunk 切新）；
 * 释放引用归零后入线程缓存或共享池；泄漏检测随分配登记、随释放清案。
 */
public final class BufServer implements BufPort {

    private final PoolArena arena = new PoolArena(256, 4);
    private final ThreadCache cache = new ThreadCache(2);
    private final LeakDetector leaks = new LeakDetector();

    @Override
    public int allocate(int size, String thread) {
        int bufId;
        if (thread != null) {
            Integer cached = cache.take(thread, arena.sizeClass(size));
            bufId = cached != null ? arena.reset(arena.buf(cached)).id() : arena.allocate(size).id();
        } else {
            bufId = arena.allocate(size).id();
        }
        leaks.track(bufId);
        return bufId;
    }

    @Override
    public void retain(int bufId) {
        arena.retain(arena.buf(bufId));
    }

    @Override
    public void release(int bufId, String thread) {
        ByteBuf buf = arena.buf(bufId);
        arena.assertAlive(buf);
        boolean freed = arena.release(buf);
        if (freed && buf.parent == 0) {
            if (thread != null) {
                Integer evicted = cache.give(thread, buf.capacity, bufId);
                if (evicted != null) {
                    arena.recycle(arena.buf(evicted));
                }
            } else {
                arena.recycle(buf);
            }
        }
        if (freed) {
            leaks.close(bufId);
        }
    }

    @Override
    public int refs(int bufId) {
        return arena.buf(bufId).refs();
    }

    @Override
    public void write(int bufId, byte[] data) {
        ByteBuf buf = arena.buf(bufId);
        arena.assertAlive(buf);
        if (data.length > buf.writable()) {
            throw new IllegalArgumentException("越界写: 需 " + data.length + " 可写 " + buf.writable());
        }
        buf.write(data);
    }

    @Override
    public byte[] read(int bufId, int length) {
        ByteBuf buf = arena.buf(bufId);
        arena.assertAlive(buf);
        if (length < 0 || length > buf.readable()) {
            throw new IllegalArgumentException("越界读: 需 " + length + " 可读 " + buf.readable());
        }
        return buf.read(length);
    }

    @Override
    public int readable(int bufId) {
        ByteBuf buf = arena.buf(bufId);
        arena.assertAlive(buf);
        return buf.readable();
    }

    @Override
    public int slice(int bufId, int index, int length) {
        return arena.slice(arena.buf(bufId), index, length, false).id();
    }

    @Override
    public int retainedSlice(int bufId, int index, int length) {
        return arena.slice(arena.buf(bufId), index, length, true).id();
    }

    @Override
    public List<Integer> leaks() {
        return leaks.leakReport();
    }

    @Override
    public int chunks() {
        return arena.chunkCount();
    }

    @Override
    public int usedBytes() {
        return arena.usedBytes();
    }

    @Override
    public List<String> segmentShape() {
        return List.of("key", "sequence", "value");
    }
}

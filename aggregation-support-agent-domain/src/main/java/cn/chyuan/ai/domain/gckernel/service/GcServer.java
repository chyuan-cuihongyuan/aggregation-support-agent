package cn.chyuan.ai.domain.gckernel.service;

import java.util.List;

/**
 * 垃圾回收组合实现（工单 1235 FI8，go 思想）。
 * 分配超阈值自动触发整周期；引用走标记期写屏障；标记期新分配黑化；
 * 分步 API 供观察并发标记与屏障行为。
 */
public final class GcServer implements GcPort {

    private final Heap heap = new Heap();
    private final GarbageCollector gc = new GarbageCollector(heap, () -> 0L);

    @Override
    public int alloc(int size) {
        int id = heap.alloc(size).id();
        gc.shadeNew(id);
        if (gc.overThreshold() && gc.phase() == GarbageCollector.Phase.IDLE) {
            gc.run();
        }
        return id;
    }

    @Override
    public void refer(int from, int to) {
        heap.refer(from, to);
        gc.barrier(to);
    }

    @Override
    public void cut(int from, int to) {
        heap.cut(from, to);
    }

    @Override
    public void root(int id) {
        heap.root(id);
    }

    @Override
    public void unroot(int id) {
        heap.unroot(id);
    }

    @Override
    public boolean alive(int id) {
        return heap.alive(id);
    }

    @Override
    public long usedBytes() {
        return heap.usedBytes();
    }

    @Override
    public long totalAllocated() {
        return heap.totalAllocated();
    }

    @Override
    public long threshold() {
        return gc.threshold();
    }

    @Override
    public void gc() {
        gc.run();
    }

    @Override
    public int cycleCount() {
        return gc.cycleCount();
    }

    @Override
    public int stwCount() {
        return gc.stwCount();
    }

    @Override
    public long stwTicks() {
        return gc.stwTicks();
    }

    @Override
    public int sweptTotal() {
        return gc.sweptTotal();
    }

    @Override
    public void startCycle() {
        gc.startCycle();
    }

    @Override
    public boolean markStep() {
        return gc.markStep();
    }

    @Override
    public void finishCycle() {
        gc.finishCycle();
    }

    @Override
    public boolean marking() {
        return gc.phase() == GarbageCollector.Phase.MARKING;
    }

    @Override
    public List<String> heapShape() {
        return List.of("opcode", "arg");
    }
}

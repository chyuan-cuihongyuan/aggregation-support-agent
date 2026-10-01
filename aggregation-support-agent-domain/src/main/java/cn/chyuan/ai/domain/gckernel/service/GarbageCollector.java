package cn.chyuan.ai.domain.gckernel.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 垃圾回收器（工单 1230 FI3 / 1231 FI4 / 1232 FI5 / 1233 FI6 / 1234 FI7，go 思想）。
 * 三色标记：根置灰→灰出队扫引用（白子置灰）→转黑，不可达留白；
 * 插入写屏障：标记期新增引用目标置灰（D6）；已黑重访幂等；
 * 清扫：白对象回收、堆计数释放、统计留痕；
 * 触发步调：使用字节超阈值（上次存活堆×2，D6）自动触发，亦可手动；
 * STW 阶段：标记准备与标记终止各一次，虚拟时钟计时长。
 */
public final class GarbageCollector {

    /** GC 阶段：IDLE 空闲/MARKING 并发标记（写屏障开）/TERMINATE 标记终止 STW/SWEEP 清扫 */
    public enum Phase {
        IDLE, MARKING, TERMINATE, SWEEP
    }

    private final Heap heap;
    private final LongSupplier clock;
    private final Set<Integer> grey = new LinkedHashSet<>();
    private final Set<Integer> black = new LinkedHashSet<>();
    private Phase phase = Phase.IDLE;
    private long threshold = 256;
    private long lastLiveBytes;
    private int cycleCount;
    private int stwCount;
    private long stwTicks;
    private int sweptTotal;
    private int markedTotal;
    private static final long TICKS_PER_STW = 3;

    public GarbageCollector(Heap heap, LongSupplier clock) {
        this.heap = heap;
        this.clock = clock;
    }

    public Phase phase() {
        return phase;
    }

    public long threshold() {
        return threshold;
    }

    public int cycleCount() {
        return cycleCount;
    }

    public int stwCount() {
        return stwCount;
    }

    public long stwTicks() {
        return stwTicks;
    }

    public int sweptTotal() {
        return sweptTotal;
    }

    public int markedTotal() {
        return markedTotal;
    }

    public long lastLiveBytes() {
        return lastLiveBytes;
    }

    /** 使用字节是否越过阈值（go pacer 简化：上次存活堆×2） */
    public boolean overThreshold() {
        return heap.usedBytes() > threshold;
    }

    /** 标记期写屏障：新增引用目标置灰；非标记期零开销 */
    public void barrier(int target) {
        if (phase == Phase.MARKING && heap.alive(target) && !black.contains(target)) {
            grey.add(target);
        }
    }

    /** 标记期新分配直接黑化（go 语义），免遭本轮清扫 */
    public void shadeNew(int id) {
        if (phase == Phase.MARKING) {
            black.add(id);
        }
    }

    /** 开启周期：STW 标记准备（根置灰），进入并发标记 */
    public void startCycle() {
        if (phase != Phase.IDLE) {
            throw new IllegalStateException("上一周期未完成");
        }
        grey.clear();
        black.clear();
        stwEnter();
        for (Integer root : heap.rootIds()) {
            if (!black.contains(root)) {
                grey.add(root);
            }
        }
        phase = Phase.MARKING;
    }

    /** 并发标记推进一步：灰出队扫引用白子置灰转黑；返回是否仍在标记 */
    public boolean markStep() {
        if (phase != Phase.MARKING) {
            return false;
        }
        if (grey.isEmpty()) {
            phase = Phase.TERMINATE;
            stwEnter();
            return false;
        }
        java.util.Iterator<Integer> it = grey.iterator();
        int current = it.next();
        it.remove();
        if (black.contains(current)) {
            return true;
        }
        for (Integer child : heap.refIds(current)) {
            if (!black.contains(child)) {
                grey.add(child);
            }
        }
        black.add(current);
        markedTotal++;
        return true;
    }

    /** 完成周期：标记终止 STW → 清扫白对象 → 步调更新 → 回到空闲 */
    public void finishCycle() {
        if (phase == Phase.MARKING) {
            phase = Phase.TERMINATE;
            stwEnter();
        }
        if (phase != Phase.TERMINATE) {
            throw new IllegalStateException("周期未在标记终止阶段");
        }
        phase = Phase.SWEEP;
        int swept = 0;
        for (Integer id : heap.aliveIds()) {
            if (!black.contains(id)) {
                heap.free(id);
                swept++;
            }
        }
        sweptTotal += swept;
        lastLiveBytes = heap.usedBytes();
        cycleCount++;
        threshold = Math.max(256, lastLiveBytes * 2);
        phase = Phase.IDLE;
    }

    /** 一把跑完：startCycle → 标记至尽 → finishCycle */
    public void run() {
        startCycle();
        while (markStep()) {
            // 并发标记推进
        }
        finishCycle();
    }

    private void stwEnter() {
        stwCount++;
        stwTicks += TICKS_PER_STW;
    }

    /** 存活名单（分配序），供清扫校验 */
    public List<Integer> liveIds() {
        return heap.aliveIds();
    }
}

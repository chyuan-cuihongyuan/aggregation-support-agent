package cn.chyuan.ai.domain.gckernel.service;

import java.util.List;

/**
 * 垃圾回收端口（工单 1235 FI8，go 思想）。
 * alloc·ref·gc 入口统一编排：堆分配记账·对象图·三色标记·插入写屏障·清扫·
 * 触发步调·STW 阶段组合管线/vmkernel 堆指令形状只读联动
 * （Op: opcode/arg 字段名对齐，不 import vmkernel）/gc-kernel.enabled 默认关（开启才改变行为）。
 */
public interface GcPort {

    /** 分配：超阈值自动触发 GC（FI1/FI6） */
    int alloc(int size);

    /** 引用边：标记期走插入写屏障（FI2/FI4） */
    void refer(int from, int to);

    /** 删边（FI4） */
    void cut(int from, int to);

    /** 根声明（FI2） */
    void root(int id);

    /** 取消根（FI2） */
    void unroot(int id);

    /** 对象是否存活（FI5） */
    boolean alive(int id);

    /** 堆使用字节（FI1/FI5） */
    long usedBytes();

    /** 累计分配字节（FI1） */
    long totalAllocated();

    /** 当前触发阈值=上次存活堆×2（FI6） */
    long threshold();

    /** 手动触发 GC（FI6） */
    void gc();

    /** GC 周期计数（FI6/FI7） */
    int cycleCount();

    /** STW 次数：每周期 2 次（FI7） */
    int stwCount();

    /** STW 累计时长（虚拟 tick，FI7） */
    long stwTicks();

    /** 累计清扫对象数（FI5） */
    int sweptTotal();

    /** 开启分步周期：STW 准备 + 进入并发标记（FI4/FI7 可观察屏障用） */
    void startCycle();

    /** 并发标记推进一步，返回是否仍在标记（FI3/FI7） */
    boolean markStep();

    /** 完成周期：终止 STW + 清扫 + 步调更新（FI5/FI7） */
    void finishCycle();

    /** 是否处于并发标记期（FI4） */
    boolean marking();

    /** vmkernel 堆指令形状只读联动（Op: opcode/arg） */
    List<String> heapShape();

    static GcPort inMemory() {
        return new GcServer();
    }
}
